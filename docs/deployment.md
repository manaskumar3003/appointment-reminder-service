# Deployment & Scaling

[← README](../README.md)

## The load

50,000 appointments a day = 100,000 reminders a day.

| Spread | Reminders |
|---|---|
| Evenly over 24h | ~70/min |
| Over a 10-hour business day | ~170/min |
| With a 3× peak | ~500/min, **~8/sec** |

One instance handles `batch-size` × polls-per-minute = 500 × 4 = **2,000/min**.
[Measured](benchmarks.md) capacity is far above that. One instance covers peak
with ~4× headroom, so scaling is about staying up, not keeping up.

## When to scale, and what to do

Watch the backlog. Everything else is a symptom of it.

```sql
-- Reminders past due and still not sent. This is the number that matters.
SELECT count(*) FROM reminders WHERE status = 'PENDING' AND scheduled_at < now();
```

| What you see | What it means | What to do |
|---|---|---|
| Backlog steady near zero | Healthy | Nothing |
| Backlog grows in short spikes, clears | Batch too small for the burst | Raise `batch-size` |
| Reminders arriving late but backlog clears | Poll interval too slow | Lower `poll-interval-ms` |
| Backlog grows and doesn't clear | Out of worker capacity | **Add an instance** |
| Backlog fine, DB CPU high | Database is the limit | Bigger instance, then read replicas for reads |
| `FAILED` count rising | The sender is broken, not the service | Fix the provider — scaling won't help |
| `PROCESSING` rows stuck past the lease | Workers are dying | Find out why before adding more |

Two other alerting queries:

```sql
SELECT count(*) FROM reminders WHERE status = 'FAILED';           -- should be 0
SELECT count(*) FROM reminders WHERE status = 'PROCESSING'
  AND processing_started_at < now() - interval '5 minutes';       -- stranded
```

**Adding an instance takes no code and no config.** `FOR UPDATE SKIP LOCKED`
means instances claim disjoint batches with no leader and no coordination — a
second instance is just a second container.

Where it stops being free: each instance holds a connection pool, so
`max_connections` on the database is the first real wall. Past ~10 instances,
put PgBouncer in front.

## Which infrastructure

| Option | Use it when | How |
|---|---|---|
| **Single VM + docker compose** | Pilot, a handful of dealerships, one person operating it | What this repo ships. `docker compose up -d` and run the jar. No redundancy — take backups. |
| **Managed Postgres + 2 containers** ⭐ | Anything real. This is the default | RDS/Cloud SQL/Neon for the database; app on ECS/Fly/Render, two instances across two AZs. The database is the thing worth paying someone to keep alive. |
| **Kubernetes** | You already run Kubernetes | `Deployment` with `replicas: 2`. No leader election, no `StatefulSet`, no `CronJob` — the lease and the constraints do that work. |
| **Serverless** | Don't, here | The worker wants a connection pool that outlives one invocation. Possible with PgBouncer, worse than a container that costs a few dollars a month. |
| **Add a message broker** | Other services need appointment events, or send volume outgrows one database | Publish events alongside the existing rows — don't move the reminders into it. [Why](design-decisions.md#why-not-kafka-rabbitmq-or-redis) |

## Operating notes

- Storage is unremarkable: ~36M reminder rows a year, a few GB. Partial indexes
  stay the size of the backlog, not the history.
- Flyway migrates on startup. Rolling deploys are safe as long as migrations stay
  backwards-compatible with the running version — add columns, never rename in
  one step.
- A reminder fires within one poll interval (15s) of its due time. Well inside
  anyone's definition of "24 hours before".
