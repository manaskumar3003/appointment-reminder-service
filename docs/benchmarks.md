# Benchmarks

[← README](../README.md)

**The brief says 50,000 appointments a day. So the benchmark does 50,000.**

```bash
./mvnw test -Dbenchmark=true -Dtest=ReminderBenchmarkTest
```

Code: [`ReminderBenchmarkTest`](../src/test/java/com/product/appointment/ReminderBenchmarkTest.java).
Disabled unless `-Dbenchmark=true`, so it never runs in a normal `./mvnw test`.

## Draining 50,000 reminders

Seed a full day's backlog in one statement — seeding is not what's being
measured, so it doesn't go through JPA:

```sql
INSERT INTO appointments (dealership_id, customer_contact, scheduled_at, status)
SELECT (i % 500) + 1, '+1555' || lpad(i::text, 7, '0'),
       now() + interval '2 hours', 'SCHEDULED'
FROM generate_series(1, 50000) i;

INSERT INTO reminders (appointment_id, reminder_type, scheduled_at, status)
SELECT id, 'TWO_HOURS', now() - interval '1 minute', 'PENDING' FROM appointments;
```

All 50,000 are due *right now*, which is the worst case the service can face.
Then four workers run the real production path until the table is empty:

```java
for (int t = 0; t < WORKERS; t++) {
    pool.submit(() -> {
        startLine.await();              // all four start together
        while (pending() > 0) {
            worker.processReminders();  // the same method @Scheduled calls
            polls.incrementAndGet();
        }
        return null;
    });
}
```

Nothing is stubbed or shortcut. Each reminder goes through the same claim with
`FOR UPDATE SKIP LOCKED`, the same `notification_log` insert, the same status
update and the same commit that a live reminder does.

### Result

| Run | 50,000 reminders drained in | Throughput | Per reminder |
|---|---|---|---|
| 1 | 15.80s | 3,164/sec | 0.32 ms |
| 2 | 14.53s | 3,441/sec | 0.29 ms |
| 3 | 15.11s | 3,310/sec | 0.30 ms |

**~3,300 reminders/second.** The test also asserts `notification_log` holds
exactly 50,000 rows — so it doubles as a dedup test at full daily volume, under
four-way contention.

Peak real demand is [~8/sec](deployment.md#the-load). The measured ceiling is
**400× that**. A full day's 100,000 reminders, if they all came due at the same
instant, clear in about **30 seconds**.

## Booking

2,000 appointments (4,000 reminder rows) through `AppointmentService`, 8 threads:

| Run | Elapsed | Throughput | Per booking |
|---|---|---|---|
| 1 | 0.19s | 10,309/sec | 0.10 ms |
| 2 | 0.19s | 10,582/sec | 0.09 ms |
| 3 | 0.18s | 10,929/sec | 0.09 ms |

**~10,000 bookings/second.** The brief's 50,000 a day averages **0.6/second**.

## Caveats

These numbers say the *design* isn't the bottleneck. They don't say the service
will do 3,300/sec in production:

- **Real sending is a network call.** An SMS provider takes 50–500ms and rate
  limits you. That, not Postgres, sets real throughput — and it's why the worker
  sends one reminder per transaction instead of batching.
- **Database is on the same host here.** A managed Postgres across an AZ adds
  ~1ms per round trip, several times per reminder. Expect the number to drop a
  lot; it has 400× of room to drop into.
- Booking and draining were measured separately, so there's no API contention.
- Stub logging was turned down to `WARN` — at 100k rows the console writes
  dominate, and a real sender's cost is the network, not stdout.

<sub>Measured on a 20-thread laptop with Postgres 17 in Testcontainers, OpenJDK 21. Run it yourself; the command is at the top.</sub>
