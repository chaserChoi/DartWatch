ALTER TABLE notification_delivery
    DROP CONSTRAINT ck_notification_delivery_status,
    DROP CONSTRAINT ck_notification_delivery_state,
    DROP CONSTRAINT uk_notification_delivery_target,
    ADD COLUMN recipient VARCHAR(320),
    ADD COLUMN next_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN retryable BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN claim_token UUID;

-- Never guess the recipient or resend a legacy in-flight delivery.
UPDATE notification_delivery SET status = 'UNKNOWN', last_error = 'LEGACY_IN_FLIGHT'
    WHERE status = 'SENDING';
UPDATE notification_delivery SET next_attempt_at = created_at WHERE status = 'PENDING';

ALTER TABLE notification_delivery
    ADD CONSTRAINT uk_notification_delivery_target UNIQUE (disclosure_id, channel, recipient),
    ADD CONSTRAINT ck_notification_delivery_recipient CHECK (
        recipient IS NULL OR (recipient = lower(btrim(recipient)) AND recipient ~ '^[^[:space:]@]+@[^[:space:]@]+$')),
    ADD CONSTRAINT ck_notification_delivery_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'SENT', 'FAILED', 'UNKNOWN', 'CANCELLED')),
    ADD CONSTRAINT ck_notification_delivery_state CHECK (
        attempt_count >= 0 AND
        ((attempt_count = 0 AND last_attempt_at IS NULL) OR (attempt_count > 0 AND last_attempt_at IS NOT NULL)) AND
        (sent_at IS NOT NULL) = (status = 'SENT') AND
        (claim_token IS NOT NULL) = (status = 'PROCESSING') AND
        (status <> 'PROCESSING' OR (attempt_count > 0 AND last_error IS NULL AND NOT retryable AND next_attempt_at IS NULL)) AND
        (status <> 'SENT' OR (attempt_count > 0 AND last_error IS NULL AND NOT retryable AND next_attempt_at IS NULL)) AND
        (status <> 'PENDING' OR (attempt_count = 0 AND last_error IS NULL AND NOT retryable)) AND
        (status NOT IN ('FAILED', 'UNKNOWN') OR (attempt_count > 0 AND last_error IS NOT NULL AND last_error ~ '[^[:space:]]')) AND
        (status NOT IN ('UNKNOWN', 'CANCELLED') OR (NOT retryable AND next_attempt_at IS NULL)) AND
        (status <> 'CANCELLED' OR last_error IS NOT NULL) AND
        (NOT retryable OR (status = 'FAILED' AND next_attempt_at IS NOT NULL)) AND
        (status <> 'FAILED' OR retryable OR next_attempt_at IS NULL)
    );

-- Keep the previous duplicate protection for legacy rows without a known recipient.
CREATE UNIQUE INDEX uk_notification_delivery_target_legacy ON notification_delivery (disclosure_id, channel)
    WHERE recipient IS NULL;
CREATE INDEX ix_notification_delivery_due ON notification_delivery (next_attempt_at, id)
    WHERE recipient IS NOT NULL AND status IN ('PENDING', 'FAILED');

CREATE TABLE notification_email_settings (
    recipient VARCHAR(320) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    enabled_since TIMESTAMP WITH TIME ZONE NOT NULL
);
