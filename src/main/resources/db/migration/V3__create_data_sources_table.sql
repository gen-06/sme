CREATE TABLE data_sources (
    id                  UUID PRIMARY KEY,
    business_id         UUID NOT NULL REFERENCES businesses (id),
    adapter_type        VARCHAR(20) NOT NULL CHECK (adapter_type IN
        ('MOBILE_MONEY', 'ECOMMERCE', 'ACCOUNTING', 'POS')),
    provider            VARCHAR(255),
    connection_status   VARCHAR(20) NOT NULL CHECK (connection_status IN
        ('PENDING', 'CONNECTED', 'ERROR', 'DISCONNECTED')),
    currency            VARCHAR(3) NOT NULL,
    connection_config   JSONB,
    last_synced_at      TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_data_sources_id_business_id UNIQUE (id, business_id)
);

CREATE INDEX idx_data_sources_business_id ON data_sources (business_id);
