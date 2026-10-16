-- Fencing token: every claim gets a fresh one, and outcome writes only land while it still
-- matches, so a stale worker whose lease expired can't overwrite a newer claim.
ALTER TABLE reminders ADD COLUMN claim_token VARCHAR(36);

-- Why the provider refused, or why the outcome is unknown.
ALTER TABLE notification_log ADD COLUMN last_error VARCHAR(500);

-- The reconciler now reads both unconfirmed states: SENDING (crashed mid-send) and UNKNOWN
-- (the provider never answered).
DROP INDEX IF EXISTS idx_notification_log_sending;
CREATE INDEX idx_notification_log_unconfirmed ON notification_log (created_at)
    WHERE status IN ('SENDING', 'UNKNOWN');

-- Late receipts arrive by provider message id.
CREATE INDEX idx_notification_log_provider_message ON notification_log (provider_message_id)
    WHERE provider_message_id IS NOT NULL;
