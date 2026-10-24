ALTER TABLE kakao_connection ADD COLUMN connection_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE notification_email_settings ADD COLUMN blocked_until TIMESTAMP WITH TIME ZONE;

CREATE TABLE notification_channel_settings (
    channel VARCHAR(32) NOT NULL CHECK (channel IN ('DISCORD','KAKAO')),
    recipient VARCHAR(320) NOT NULL,
    enabled BOOLEAN NOT NULL,
    enabled_since TIMESTAMP WITH TIME ZONE NOT NULL,
    blocked_until TIMESTAMP WITH TIME ZONE,
    PRIMARY KEY (channel,recipient),
    CHECK ((channel='DISCORD' AND recipient ~ '^discord:[0-9a-f]{64}$')
        OR (channel='KAKAO' AND recipient ~ '^kakao:[0-9a-f-]{36}$'))
);
CREATE VIEW notification_targets AS
    SELECT 'EMAIL'::varchar AS channel, recipient, enabled, enabled_since, blocked_until FROM notification_email_settings
    UNION ALL
    SELECT channel, recipient, enabled, enabled_since, blocked_until FROM notification_channel_settings;

CREATE TABLE watched_notification_channel (
    watched_company_id BIGINT NOT NULL REFERENCES watched_company(id) ON DELETE CASCADE,
    channel VARCHAR(32) NOT NULL CHECK (channel IN ('EMAIL','DISCORD','KAKAO')),
    enabled BOOLEAN NOT NULL,
    enabled_since TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (watched_company_id,channel)
);
INSERT INTO watched_notification_channel
    SELECT id,'EMAIL',TRUE,coalesce(notifications_enabled_since,created_at) FROM watched_company;

ALTER TABLE notification_delivery DROP CONSTRAINT ck_notification_delivery_channel,
    DROP CONSTRAINT ck_notification_delivery_recipient,
    ADD COLUMN external_message_id VARCHAR(20),
    ADD CONSTRAINT ck_notification_delivery_channel CHECK (channel IN ('EMAIL','DISCORD','KAKAO')),
    ADD CONSTRAINT ck_notification_delivery_recipient CHECK (
        (channel='EMAIL' AND (recipient IS NULL OR
            (recipient=lower(btrim(recipient)) AND recipient ~ '^[^[:space:]@]+@[^[:space:]@]+$')))
        OR (channel='DISCORD' AND recipient IS NOT NULL AND recipient ~ '^discord:[0-9a-f]{64}$')
        OR (channel='KAKAO' AND recipient IS NOT NULL AND recipient ~ '^kakao:[0-9a-f-]{36}$')),
    ADD CONSTRAINT ck_notification_external_message CHECK (external_message_id IS NULL OR
        (status='SENT' AND external_message_id ~ '^[1-9][0-9]{0,19}$'));
