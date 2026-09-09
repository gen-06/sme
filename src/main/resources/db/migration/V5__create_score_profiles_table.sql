CREATE TABLE score_profiles (
    id                UUID PRIMARY KEY,
    business_id       UUID NOT NULL REFERENCES businesses (id),
    score             NUMERIC(5, 2) NOT NULL,
    confidence        NUMERIC(4, 3) NOT NULL,
    factor_breakdown  JSONB NOT NULL,
    model_version     VARCHAR(50) NOT NULL,
    model_type        VARCHAR(20) NOT NULL CHECK (model_type IN ('RULE_BASED', 'ML')),
    window_start      TIMESTAMPTZ,
    window_end        TIMESTAMPTZ,
    generated_at      TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    updated_at        TIMESTAMPTZ NOT NULL
);

CREATE INDEX idx_score_profiles_business_generated ON score_profiles (business_id, generated_at DESC);
