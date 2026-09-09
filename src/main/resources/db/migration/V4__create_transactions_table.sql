CREATE TABLE transactions (
    id                  UUID PRIMARY KEY,
    data_source_id      UUID NOT NULL,
    business_id         UUID NOT NULL,
    external_reference  VARCHAR(255) NOT NULL,
    amount              NUMERIC(19, 4) NOT NULL,
    currency            VARCHAR(3) NOT NULL,
    transaction_date    TIMESTAMPTZ NOT NULL,
    direction           VARCHAR(10) NOT NULL CHECK (direction IN ('INFLOW', 'OUTFLOW')),
    counterparty        VARCHAR(255),
    source_type         VARCHAR(20) NOT NULL CHECK (source_type IN
        ('MOBILE_MONEY', 'ECOMMERCE', 'ACCOUNTING', 'POS')),
    status              VARCHAR(10) NOT NULL CHECK (status IN ('COMPLETED', 'FAILED')),
    created_at          TIMESTAMPTZ NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_transactions_data_source_external_ref UNIQUE (data_source_id, external_reference),
    CONSTRAINT fk_transactions_data_source FOREIGN KEY (data_source_id, business_id)
        REFERENCES data_sources (id, business_id)
);

CREATE INDEX idx_transactions_business_id_date ON transactions (business_id, transaction_date);
