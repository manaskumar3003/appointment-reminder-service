-- A send is now intent -> provider call -> confirm. A crash between the call and the commit
-- leaves a visible SENDING row instead of no trace at all.
ALTER TABLE notification_log ADD COLUMN status VARCHAR(20);
UPDATE notification_log SET status = 'SENT';
ALTER TABLE notification_log ALTER COLUMN status SET NOT NULL;

-- What the provider called the message; null until it confirms.
ALTER TABLE notification_log ADD COLUMN provider_message_id VARCHAR(100);

-- created_at is when the intent was recorded; sent_at becomes when the provider accepted it.
ALTER TABLE notification_log ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
UPDATE notification_log SET created_at = sent_at;
ALTER TABLE notification_log ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE notification_log ALTER COLUMN created_at SET DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE notification_log ALTER COLUMN sent_at DROP NOT NULL;
ALTER TABLE notification_log ALTER COLUMN sent_at DROP DEFAULT;

-- The reconciler only reads unconfirmed rows.
CREATE INDEX idx_notification_log_sending ON notification_log (created_at) WHERE status = 'SENDING';
