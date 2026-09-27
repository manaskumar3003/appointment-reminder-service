# Design Decisions

[← README](../README.md)

## Why Postgres

- The appointments need a database anyway — so the reminders are just rows next to them.
- An appointment and its two reminders are written in **one transaction**. An appointment can never exist without the reminders it owes.
- `FOR UPDATE SKIP LOCKED` turns a table into a queue. Workers take different rows without talking to each other.
- A unique constraint is a real guarantee. "Never twice" is enforced by the database, not by code someone has to remember to write.
- Everything is visible with `SELECT` — backlog, failures, what was sent and when. One thing to run, one thing to back up.

## Why not Kafka, RabbitMQ, or Redis

The reminders table already is the queue. A broker wouldn't remove a single line of the code that makes this correct.

- **They all redeliver.** Kafka, RabbitMQ and SQS are at-least-once, so I'd still need the unique constraint to stop duplicates. The broker adds nothing — I'd just be running two systems instead of one.
- **Reminders are months out.** "Send this in 90 days" is a weak spot for brokers: Kafka has no delay feature, RabbitMQ needs a plugin. A `scheduled_at` column and an index just works.
- **Cancelling has to un-send.** You can't pull a message back out of a Kafka topic. Here it's one `UPDATE`.
- **Redis is memory first.** A failover can lose reminders, and it becomes a second source of truth to keep in sync. Losing the record that we already texted someone is worse than losing the appointment.

**Worth adding a broker when:** other services need appointment events (billing, analytics, dealership dashboards), or volume outgrows one database. Neither is true at 50,000 a day.

## How "never twice" works

Three layers, and only the last one is the real proof:

- `UNIQUE(appointment_id, reminder_type)` — an appointment can't grow a second 24h or 2h reminder.
- The claim flips `PENDING → PROCESSING` in the same statement that selects it, under `SKIP LOCKED` — two workers can't grab the same row.
- `UNIQUE(reminder_id)` on `notification_log` — **this** is the guarantee. Every send inserts there first, so a duplicate is refused by Postgres. It holds even if the other two are wrong.

That insert lives in `ReminderService`, not inside the stub sender, so the guarantee belongs to the service — a real SMS sender dropped in later can't forget it.

Two details that matter:

- The log row and the `SENT` status commit **together**, so there's no crash window where a reminder is marked sent with no record, or recorded with no mark.
- What the database guarantees is **at most one recorded delivery**. The send itself is **at-least-once**: it happens before the commit, so a crash between the two rolls the log row back and the reminder is retried and sent again. That holds for the stub too — its log line is printed before the commit. The window is milliseconds wide and needs a crash inside it, but no design can make an external send and a database commit one atomic step. Closing it means committing the decision to send *before* sending, and flagging anything left unconfirmed rather than retrying it — see [Roadmap](roadmap.md#-real-sender--reconciliation).
- If two workers somehow race — a send outlived its lease and the reminder was reclaimed — the loser's insert waits on the winner's, then breaks the constraint and rolls back before it reaches the sender. Its failure is recorded with a conditional `UPDATE … WHERE status = 'PROCESSING'`, so it can't overwrite the winner's `SENT`. One notification, and the status stays true.

Proven by `NoDuplicateReminderTest.concurrentWorkersCannotNotifyTheSameCustomerTwice` — eight workers, twenty due reminders, batch size pinned to 1 so they actually fight over them. All twenty sent, none twice.

## Why two instances — and does one work?

- **One works fine.** It handles 2,000 reminders a minute against a peak need of ~500. Nothing here needs a second node to be *correct*.
- **The second is for staying up, not keeping up.** With one instance, a crash means nothing goes out until it restarts, and whatever it had claimed sits stuck until then.
- **With two, the survivor cleans up.** Rows the dead worker claimed are reclaimed after the lease expires and sent normally.
- **Adding one costs nothing.** No leader election, no sharding, no scheduler to disable — just another container. That's what Postgres bought here.

[When to add a third →](deployment.md)

## Corner cases

- **Booked 3 hours out** — the 24h reminder is marked `SKIPPED`, not sent late. Don't tell someone their appointment is in 24 hours when it's in 3.
- **Appointment cancelled** — pending reminders are skipped. Cancelling twice returns 200, because a retried cancel is normal, not an error.
- **Cancelled *after* its reminder was claimed** — cancellation only touches `PENDING` rows, so the worker re-checks the appointment before sending and skips it. Otherwise the customer gets a reminder for an appointment they called off.
- **Service was down for hours** — the backlog contains reminders for appointments that have already happened. Those are skipped, not sent. Being late is fine; telling someone their appointment is in 2 hours when it was this morning is not.
- **Worker killed mid-send** — `PROCESSING` is a lease, not a lock. Anything older than 5 minutes goes back to `PENDING` and gets retried. A lock held by a dead JVM would be invisible.
- **A reminder that kills its worker every time** — the reaper respects the attempt cap too, so it lands in `FAILED` instead of looping forever.
- **Instances with drifting clocks** — due-ness and the lease are both decided by the database's clock, not each app's.
- **Deploy during a batch** — graceful shutdown lets in-flight sends finish instead of stranding them.
- **A send that keeps failing** — held back 5 minutes between attempts, then parked in `FAILED`. The failures worth retrying are rate limits and outages, so retrying on the next 15-second poll would spend all three attempts inside a minute and dead-letter everything a two-minute blip touched.
- **The poll itself throws** — for example the database is briefly unreachable. Caught and logged with what failed, and the next poll retries. Nothing is lost: every claim is either committed or rolled back, and a stranded claim is reclaimed by the lease.
- **Bad timestamp or blank contact** — 400, not 500.
