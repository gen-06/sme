CREATE TABLE consumers (
    id                       UUID PRIMARY KEY,
    name                     VARCHAR(255) NOT NULL,
    contact_email            VARCHAR(255),
    api_key_hash             VARCHAR(64) NOT NULL UNIQUE,
    api_key_prefix           VARCHAR(16) NOT NULL,
    oauth_client_id          VARCHAR(255),
    oauth_client_secret_hash VARCHAR(255),
    status                   VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'SUSPENDED', 'REVOKED')),
    last_used_at             TIMESTAMPTZ,
    created_at               TIMESTAMPTZ NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL
);

CREATE TABLE consumer_scopes (
    consumer_id UUID NOT NULL REFERENCES consumers (id),
    scope       VARCHAR(30) NOT NULL CHECK (scope IN
        ('BUSINESS_WRITE', 'DATA_SOURCE_WRITE', 'SYNC_TRIGGER', 'SCORE_READ', 'TRANSACTION_READ')),
    PRIMARY KEY (consumer_id, scope)
);
