CREATE TABLE IF NOT EXISTS notifications (
    id               BIGSERIAL PRIMARY KEY,
    message_id       BIGINT NOT NULL,
    conversation_id  BIGINT NOT NULL,
    sender_id        BIGINT NOT NULL,
    recipient_id     BIGINT NOT NULL,
    preview          VARCHAR(200) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'SENT',
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Idempotent consumer: the same event delivered twice (at-least-once)
    -- can never create two notifications for the same recipient.
    CONSTRAINT uq_notification_message_recipient UNIQUE (message_id, recipient_id)
);

CREATE INDEX IF NOT EXISTS idx_notifications_recipient
    ON notifications (recipient_id, created_at DESC);
