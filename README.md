<h1 align="center">Appointment Reminder Service</h1>

<p align="center">
Books vehicle service appointments and reminds the customer 24 hours and 2 hours before — <b>never the same reminder twice</b>, enforced by the database, not by hope.
</p>

<p align="center">
<a href="docs/architecture.md">Architecture</a> ·
<a href="docs/design-decisions.md">Design Decisions</a> ·
<a href="docs/deployment.md">Deployment &amp; Scaling</a> ·
<a href="docs/benchmarks.md">Benchmarks</a> ·
<a href="docs/roadmap.md">Roadmap</a>
</p>

> **Since submission.** Submitted on 23 Sep at
> [`ff08af4`](https://github.com/manaskumar3003/appointment-reminder-service/tree/ff08af4).
> A later review of my own code found two bugs, fixed in `f4607b2` with tests that
> fail on the submitted version:
> - Spring's own errors (405, 415, 404 for an unknown path, 400 for a non-numeric id)
>   came back as 500s, because the catch-all exception handler claimed them.
> - A worker that lost a lease race could overwrite a delivered reminder's `SENT`
>   with `PENDING` — or `FAILED` on its last attempt. The customer still got exactly
>   one notification; the recorded status was wrong.
>
> Same commit: `/actuator/health`, raw-SQL tests proving the constraints hold without
> the application, tests split by layer, and corrected claims about `@Scheduled` and
> exactly-once delivery.

## How to run

Needs Docker and JDK 21. Nothing else — Maven comes with the wrapper.

```bash
docker compose up -d          # Postgres on localhost:5431
./mvnw spring-boot:run        # Flyway migrates on startup, worker starts polling
```

Book an appointment two days out:

```bash
curl -s localhost:8080/appointments \
  -H 'Content-Type: application/json' \
  -d "{\"dealershipId\":1,\"customerContact\":\"+15550001111\",\"scheduledAt\":\"$(date -u -d '+2 days' +%Y-%m-%dT%H:%M:%SZ)\"}"
```

The response shows both reminders it scheduled for you:

```json
{
  "id": 1,
  "dealershipId": 1,
  "customerContact": "+15550001111",
  "scheduledAt": "2026-09-23T11:30:00Z",
  "status": "SCHEDULED",
  "reminders": [
    {"id": 1, "type": "TWENTY_FOUR_HOURS", "scheduledAt": "2026-09-22T11:30:00Z", "status": "PENDING"},
    {"id": 2, "type": "TWO_HOURS",         "scheduledAt": "2026-09-23T09:30:00Z", "status": "PENDING"}
  ]
}
```

Same rows in the database:

```bash
docker compose exec postgres psql -U postgres -d appointments \
  -c "SELECT id, reminder_type, scheduled_at, status FROM reminders;"
```

To watch one actually fire, pull its send time into the past and wait for the
next poll (15s):

```bash
docker compose exec postgres psql -U postgres -d appointments \
  -c "UPDATE reminders SET scheduled_at = now() - interval '1 minute' WHERE id = 1;"
```

The service logs the payload instead of sending anything:

```
NOTIFICATION reminderId=1 appointmentId=1 type=TWENTY_FOUR_HOURS contact=+15****11 appointmentAt=2026-09-23T11:30:00Z messageId=stub-3f9c…
```

The contact is masked: enough to tell two customers apart in the logs, not enough to leak one.

And the delivery is on record — one row per reminder, forever:

```bash
docker compose exec postgres psql -U postgres -d appointments \
  -c "SELECT * FROM notification_log;"
```

### Endpoints

| | |
|---|---|
| `POST /appointments` | Book. Returns 201 with both reminders, or 400 if the slot is in the past or the contact is blank. |
| `GET /appointments/{id}` | Read one, with its reminders and their current status. 404 if unknown. |
| `POST /appointments/{id}/cancel` | Cancel, and skip its pending reminders. Idempotent. |
| `GET /actuator/health` | `UP` when the app and its database are reachable. The only actuator endpoint exposed. |

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) `application/problem+json`.
Spring's own errors keep their real status — 405 for a wrong method, 415 for a
wrong content type, 400 for a non-numeric id — rather than collapsing into 500.

### Tests

```bash
./mvnw test
```

Spins up a real Postgres via Testcontainers. The headline test races eight
workers over the same twenty due reminders and asserts all twenty were sent and
none twice — see [Design Decisions](docs/design-decisions.md#how-never-twice-works).

Benchmarks are excluded from that run. To measure throughput on your own machine:

```bash
./mvnw test -Dbenchmark=true -Dtest=ReminderBenchmarkTest
```

### Configuration

Everything tunable lives under `reminders.*` in `src/main/resources/application.yaml`:

| Key | Default | |
|---|---|---|
| `batch-size` | 500 | Reminders claimed per poll, per instance. |
| `poll-interval-ms` | 15000 | How often a worker looks for due reminders. |
| `max-attempts` | 3 | Retries before a reminder is parked in `FAILED`. |
| `retry-delay-minutes` | 5 | How long a failed reminder waits before it's eligible again. |
| `lease-minutes` | 5 | How long a claim may sit in `PROCESSING` before it is assumed dead and retried. |

Database credentials come from the environment — `DB_URL`, `DB_USERNAME`,
`DB_PASSWORD`, `DB_POOL_MAX` — defaulting to the docker-compose values so a local
run needs no setup and no credential is baked into the image.

## What the brief asked for

| Requirement | Where |
|---|---|
| `POST /appointments` with a time and a contact | [`AppointmentController`](src/main/java/com/product/appointment/controller/AppointmentController.java), [`AppointmentService`](src/main/java/com/product/appointment/service/AppointmentService.java) |
| Reminders 24h and 2h before | Written with the appointment in one transaction. Test: `createsOneReminderTwentyFourHoursOutAndOneTwoHoursOut` |
| Stub sender that logs the payload | [`StubNotificationSender`](src/main/java/com/product/appointment/notification/StubNotificationSender.java) |
| Never the same reminder twice, provably | `UNIQUE(reminder_id)` on `notification_log`. Tests: `concurrentWorkersCannotNotifyTheSameCustomerTwice`, `databaseRefusesASecondDeliveryOfTheSameReminder`, and 50,000 reminders under four workers in the [benchmark](docs/benchmarks.md). [How it works](docs/design-decisions.md#how-never-twice-works) |
| 50,000 appointments a day, 500 dealerships | [Measured](docs/benchmarks.md) at ~3,500 reminders/sec as submitted, about a third less since the send records its intent first; [scaling notes](docs/deployment.md) |
| Corner cases and failures | [Corner cases](docs/design-decisions.md#corner-cases), tested under [`src/test`](src/test/java/com/product/appointment) — except clock drift, graceful shutdown and a poll that throws, which are handled by design but not tested |
| Tests | `./mvnw test`, against a real Postgres |
| How to run, design decisions | This page, [Design Decisions](docs/design-decisions.md) |
| Design diagram | [Architecture](docs/architecture.md) |
| Another week | [Roadmap](docs/roadmap.md) |
| Open questions | Below |

Anyone can check the guarantee against a live database. Both must return no rows:

```sql
-- No reminder delivered twice
SELECT reminder_id, count(*) FROM notification_log GROUP BY reminder_id HAVING count(*) > 1;
-- No appointment with two reminders of the same type
SELECT appointment_id, reminder_type, count(*) FROM reminders
GROUP BY appointment_id, reminder_type HAVING count(*) > 1;
```

They can't return rows: both are backed by unique constraints, and the tests
show Postgres refusing a raw `INSERT` that bypasses the application entirely.

## Open questions

The brief left these to judgement. Current answers, all cheap to change:

1. **Booking inside the reminder window.** An appointment three hours out has a
   24h reminder whose time already passed. Firing it immediately would be absurd,
   so it is recorded `SKIPPED` rather than sent late. The alternative reading —
   send it right away because the customer should hear *something* — is a
   one-line change.
2. **What "contact" is.** Treated as an opaque string. A real system needs a
   channel (SMS/email/push), and the validation and sender differ per channel.
3. **Rescheduling.** Not implemented — only cancel. A reschedule endpoint has to
   decide whether an already-sent reminder should be re-sent for the new time.
4. **Timezones.** Stored as `timestamptz`, so instants are unambiguous. But
   "24 hours before" in a dealership's local time crosses DST differently than in
   UTC, and the brief does not say which the customer expects.
5. **Duplicate bookings.** A double-clicked form creates two appointments and two
   sets of reminders. The customer gets two notifications — for two genuinely
   distinct appointments. An `Idempotency-Key` header would collapse them; see
   [Roadmap](docs/roadmap.md).
