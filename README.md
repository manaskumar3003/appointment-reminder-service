<h1 align="center">Appointment Reminder Service</h1>

<p align="center">
Books vehicle service appointments and reminds the customer 24 hours and 2 hours before — <b>exactly once</b>, enforced by the database, not by hope.
</p>

<p align="center">
<a href="docs/architecture.md">Architecture</a> ·
<a href="docs/design-decisions.md">Design Decisions</a> ·
<a href="docs/deployment.md">Deployment &amp; Scaling</a> ·
<a href="docs/benchmarks.md">Benchmarks</a> ·
<a href="docs/roadmap.md">Roadmap</a>
</p>

<br>

<table align="center">
<tr><td align="center" width="700">

### ▶ &nbsp; Watch the demo &nbsp;·&nbsp; 5 min

<a href="REPLACE_WITH_LOOM_LINK"><b>loom.com/share/…</b></a>

Creating an appointment over HTTP, the reminders firing in the service logs,
and the matching rows in Postgres.

</td></tr>
</table>

<br>

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
NOTIFICATION reminderId=1 appointmentId=1 type=TWENTY_FOUR_HOURS contact=+15550001111 appointmentAt=2026-09-23T11:30:00Z
```

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
| `lease-minutes` | 5 | How long a claim may sit in `PROCESSING` before it is assumed dead and retried. |

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
