CREATE TABLE kakao_connection (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    status VARCHAR(24) NOT NULL CHECK (status IN ('CONNECTED','REFRESHING','RECONNECT_REQUIRED')),
    client_fingerprint VARCHAR(64) NOT NULL CHECK (client_fingerprint ~ '^[0-9a-f]{64}$'),
    access_ciphertext TEXT,
    refresh_ciphertext TEXT,
    access_expires_at TIMESTAMP WITH TIME ZONE,
    refresh_expires_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_error VARCHAR(80) CHECK (last_error IS NULL OR last_error ~ '^[A-Z_]{1,80}$'),
    CHECK ((status = 'RECONNECT_REQUIRED' AND access_ciphertext IS NULL AND refresh_ciphertext IS NULL
            AND access_expires_at IS NULL AND refresh_expires_at IS NULL)
        OR (status IN ('CONNECTED','REFRESHING') AND access_ciphertext IS NOT NULL AND refresh_ciphertext IS NOT NULL
            AND access_ciphertext LIKE 'v1:%' AND refresh_ciphertext LIKE 'v1:%'
            AND access_expires_at IS NOT NULL AND refresh_expires_at IS NOT NULL))
);
