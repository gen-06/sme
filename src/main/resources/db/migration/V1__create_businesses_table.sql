CREATE TABLE businesses (
    id                    UUID PRIMARY KEY,
    name                  VARCHAR(255) NOT NULL,
    country               VARCHAR(2) NOT NULL,
    industry              VARCHAR(255),
    registration_number   VARCHAR(255),
    registration_date     DATE,
    onboarding_date       DATE NOT NULL,
    status                VARCHAR(20) NOT NULL CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'REJECTED')),
    created_at            TIMESTAMPTZ NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_businesses_country ON businesses (country);
CREATE INDEX idx_businesses_status ON businesses (status);
