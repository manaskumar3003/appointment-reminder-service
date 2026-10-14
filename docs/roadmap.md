# Roadmap

[← README](../README.md)

What another week would add, in the order I'd do it.

## Next week

| | What | Why it matters |
|---|---|---|
| 🔴 | **Real sender** | Intent + reconciliation are in; a real provider isn't — see below |
| 🔴 | **Idempotency keys on booking** | A double-clicked form books twice today |
| 🟡 | **Metrics** | Health is exposed; everything else is still a `SELECT` |
| 🟡 | **Rescheduling** | Only cancel exists |
| 🟢 | **Channel-aware sending** | SMS vs email need different validation and senders |
| 🟢 | **Quiet hours** | A 2h reminder for an 07:00 slot fires at 05:00 |

🔴 correctness · 🟡 operability · 🟢 product

---

### 🔴 Real sender

**Done since submission:** intent before the call. `notification_log` has a
status of its own (`SENDING` → `SENT`) and a `provider_message_id`; the row is
committed before the provider is called with an idempotency key, and a
reconciler asks the provider about anything left in limbo. See
[How "never twice" works](design-decisions.md#how-never-twice-works).

**Still the biggest gap between this and production:** a real provider.

- An HTTP client with timeouts, and errors sorted by what they mean: a
  permanent 4xx goes straight to `FAILED`, a 429 or 5xx backs off, and a
  **timeout** is ambiguous — it stays `SENDING` for the reconciler rather than
  being blindly retried.
- A provider that honours idempotency keys on its side. The stub keeps them in
  memory, so they don't survive its restart.
- Delivery-receipt webhooks, so "accepted by the provider" can become
  "delivered to the phone".

### 🔴 Idempotency keys on booking

Two clicks, two appointments, two sets of reminders. Both rows are legitimate —
the service is right and the customer is still annoyed. An `Idempotency-Key`
header with a unique index, returning the original response on a repeat. Easy to
build, easy to get subtly wrong, so it needs a concurrent test, not just a
sequential one.

### 🟡 Metrics

- `/actuator/health` is there, with a database check. Next: separate liveness
  and readiness probes, so a slow start isn't mistaken for a dead instance.
- Counters for sent / failed / skipped / recovered, and a gauge for the past-due
  backlog. The [alerting queries](deployment.md#when-to-scale-and-what-to-do)
  become dashboards.
- A trace id per poll, so one reminder's path can be followed through the logs.

### 🟡 Rescheduling

Has a real design question behind it: if the 24h reminder already went out and
the appointment moves three days later, does the customer get another one?
Probably yes — but `UNIQUE(appointment_id, reminder_type)` says no. The schema
has to change (a `generation` column, or reminders keyed to a reschedule event).
Worth doing carefully, because the constraint being worked around is the one
holding up the main guarantee.

### 🟢 Channel-aware sending & quiet hours

`customerContact` is an opaque string today. A real system knows whether it's a
phone or an email, validates accordingly, and routes to a different sender —
which is the point where `NotificationSender` genuinely earns a second
implementation, and where per-channel rate limits start to matter more than the
database work.

---

## Also on the list

- [ ] **Exponential backoff** — retries are a flat 5 minutes today, which is fine for a blip and slow for a long outage
- [ ] **Harder tests** — kill a real forked instance mid-batch; an intermittently
      failing sender; sustained booking *and* draining at once
- [ ] **Squash the migrations** — five Flyway files, two of which fix the first
- [ ] Pagination on any future list endpoint, before someone asks for all 50,000
- [ ] An appointment-completed status and endpoint, once something actually closes them out
