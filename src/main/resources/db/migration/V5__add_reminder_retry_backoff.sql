-- Without a backoff a failed reminder is re-claimed on the very next poll, so three
-- attempts burn in 45 seconds and a two-minute provider outage fails everything for good.
ALTER TABLE reminders ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE;
