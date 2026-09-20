-- BIGSERIAL on a foreign key means DEFAULT nextval, which can invent an appointment id.
ALTER TABLE reminders ALTER COLUMN appointment_id DROP DEFAULT;
DROP SEQUENCE IF EXISTS reminders_appointment_id_seq;

-- Redundant: the unique constraint already indexes appointment_id as its leading column.
DROP INDEX IF EXISTS idx_reminders_appointment;

-- The worker only reads PENDING rows. A full index grows forever at 100k/day.
DROP INDEX IF EXISTS idx_reminders_due;
CREATE INDEX idx_reminders_due ON reminders (scheduled_at) WHERE status = 'PENDING';

-- The reaper scans for stranded leases.
CREATE INDEX idx_reminders_processing ON reminders (processing_started_at)
    WHERE status = 'PROCESSING';
