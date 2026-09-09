CREATE TABLE usage_records (
    id               UUID PRIMARY KEY,
    consumer_id      UUID NOT NULL REFERENCES consumers (id),
    endpoint         VARCHAR(500) NOT NULL,
    method           VARCHAR(10) NOT NULL,
    called_at        TIMESTAMPTZ NOT NULL,
    response_status  INTEGER NOT NULL
);

CREATE INDEX idx_usage_records_consumer_called_at ON usage_records (consumer_id, called_at);
