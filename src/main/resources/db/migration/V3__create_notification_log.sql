CREATE TABLE IF NOT EXISTS notification_log (
                                  id BIGSERIAL PRIMARY KEY,
                                  reminder_id BIGINT NOT NULL,
                                  sent_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                  CONSTRAINT fk_notification_reminder
                                      FOREIGN KEY (reminder_id)
                                          REFERENCES reminders(id),

                                  CONSTRAINT uk_notification_reminder
                                      UNIQUE (reminder_id)
);