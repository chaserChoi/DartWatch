ALTER TABLE disclosure
    ADD COLUMN company_name VARCHAR(200),
    ADD COLUMN filer_name VARCHAR(255),
    ADD COLUMN remarks VARCHAR(1000);

ALTER TABLE watched_company
    ADD COLUMN initial_sync_through DATE,
    ADD COLUMN last_successful_collection_date DATE,
    ADD COLUMN last_collection_attempt_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_collection_succeeded_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_collection_error VARCHAR(80),
    ADD COLUMN last_fetched_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_inserted_count INTEGER NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_watched_collection_dates CHECK (
        last_successful_collection_date IS NULL OR
        (initial_sync_through IS NOT NULL AND last_successful_collection_date >= initial_sync_through)),
    ADD CONSTRAINT ck_watched_collection_counts CHECK (
        last_fetched_count >= 0 AND last_inserted_count >= 0 AND last_inserted_count <= last_fetched_count);

CREATE TABLE company_directory_sync (
    singleton_id BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton_id),
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    company_count INTEGER NOT NULL CHECK (company_count > 0)
);
