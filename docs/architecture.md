# Architecture

[← README](../README.md)

Two independent paths share one table. Booking writes reminder rows; the worker
drains them. Neither knows about the other, which is what lets them scale apart.

```mermaid
flowchart LR
    C([Customer]) -->|POST /appointments| API[AppointmentController]
    API --> AS[AppointmentService]
    AS -->|"1 transaction:<br/>appointment + 2 reminders"| DB[(Postgres)]

    subgraph instances["Service instances (N)"]
        W1[ReminderWorker]
        W2[ReminderWorker]
    end

    W1 -->|"claim batch<br/>SKIP LOCKED"| DB
    W2 -->|"claim batch<br/>SKIP LOCKED"| DB
    W1 -->|"INSERT notification_log<br/>UNIQUE reminder_id"| DB
    W2 -->|"INSERT notification_log<br/>UNIQUE reminder_id"| DB
    W1 --> NS[StubNotificationSender]
    W2 --> NS
    NS -.->|log payload| OUT([stdout])
```

## Tables

```mermaid
erDiagram
    appointments ||--o{ reminders : "owes"
    reminders ||--o| notification_log : "delivered as"

    appointments {
        bigserial id PK
        bigint dealership_id
        varchar customer_contact
        timestamptz scheduled_at
        varchar status "SCHEDULED | CANCELLED | COMPLETED"
    }
    reminders {
        bigserial id PK
        bigint appointment_id FK
        varchar reminder_type "UNIQUE with appointment_id"
        timestamptz scheduled_at "when to send"
        varchar status "PENDING | PROCESSING | SENT | FAILED | SKIPPED"
        int attempt_count
        timestamptz processing_started_at "lease"
    }
    notification_log {
        bigserial id PK
        bigint reminder_id FK "UNIQUE"
        varchar status "SENDING | SENT"
        varchar provider_message_id
        timestamptz created_at "intent recorded"
        timestamptz sent_at "provider accepted"
    }
```

Two constraints carry the correctness of the whole service:

- `UNIQUE (appointment_id, reminder_type)` — an appointment can never accumulate
  a second 24h or 2h reminder, however many times booking is retried.
- `UNIQUE (reminder_id)` on `notification_log` — a reminder can never be
  delivered twice, however many workers race.

## The reminder's life

```mermaid
stateDiagram-v2
    [*] --> PENDING: booked, send time in the future
    [*] --> SKIPPED: booked, send time already passed

    PENDING --> PROCESSING: claimed by a worker
    PROCESSING --> SENT: SENDING row, provider call, SENT committed
    PROCESSING --> PENDING: send failed, attempts left
    PROCESSING --> PENDING: lease expired (worker died)
    PROCESSING --> FAILED: send failed, attempts exhausted
    PENDING --> SKIPPED: appointment cancelled
    PROCESSING --> SKIPPED: cancelled after claim, or appointment already started

    SENT --> [*]
    FAILED --> [*]
    SKIPPED --> [*]
```

`PROCESSING` is a lease, not a lock. It survives the process that took it, which
is the point: a lock held by a dead JVM is invisible, a `PROCESSING` row with an
old `processing_started_at` is not.

## One poll

```mermaid
sequenceDiagram
    participant W as ReminderWorker
    participant S as ReminderService
    participant DB as Postgres
    participant N as StubNotificationSender

    W->>S: recoverStaleReminders()
    S->>DB: PROCESSING older than lease → PENDING

    W->>S: reconcileUnconfirmed()
    S->>N: findSent(key) for SENDING rows no retry will revisit
    S->>DB: SENT if the provider has it, else clear the intent

    W->>S: claimBatch()
    Note over S,DB: tx 1 — the claim
    S->>DB: UPDATE … WHERE id IN (SELECT … FOR UPDATE SKIP LOCKED)
    DB-->>S: ids, now PROCESSING
    Note over S,DB: committed before any send

    loop each claimed id
        W->>S: sendClaimed(id)
        Note over S,DB: tx 2 — record the intent
        S->>S: cancelled? already started? → SKIPPED
        S->>DB: INSERT notification_log SENDING (UNIQUE reminder_id)
        Note over S,N: no transaction open
        S->>N: send(reminder, key = reminder-ID)
        N-->>S: message id (the original one for a repeated key)
        Note over S,DB: tx 3 — record the answer
        S->>DB: log row → SENT + message id, reminder → SENT
    end
```

The `notification_log` insert sits in `ReminderService`, not in the sender, so the
"never twice" guarantee doesn't depend on which `NotificationSender` is wired in.

The claim commits **before** any sending. Two reasons, both load-bearing:

1. Holding the claim open across the send would mean the sender's insert into
   `notification_log` blocks on the foreign-key lock the claim still holds — the
   worker would wait on itself until the lease expired.
2. A worker that dies after the claim leaves durable `PROCESSING` rows. The
   reaper can see them. An uncommitted claim would simply roll back — which is
   also safe, but slower to notice than it looks under a partial network failure.

The loop lives in the worker rather than the service because each step must be
its own transaction, and a service calling its own methods bypasses the Spring
proxy — `@Transactional` would silently do nothing. Inside `sendClaimed` the
two transactions around the provider call use a `TransactionTemplate` for the
same reason.
