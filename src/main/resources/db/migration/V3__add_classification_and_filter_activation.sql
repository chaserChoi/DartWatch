ALTER TABLE disclosure ADD COLUMN classification_checked_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX ix_disclosure_unclassified ON disclosure (company_id, receipt_date, id)
    WHERE disclosure_type IS NULL;

ALTER TABLE watched_company
    ADD COLUMN notifications_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN notifications_enabled_since TIMESTAMP WITH TIME ZONE,
    ADD CONSTRAINT ck_watched_notification_activation CHECK (
        NOT notifications_enabled OR notifications_enabled_since IS NOT NULL);

-- Preserve legacy rules; a NULL baseline makes them ineligible until explicitly activated via the API.
ALTER TABLE notification_rule ADD COLUMN enabled_since TIMESTAMP WITH TIME ZONE;
