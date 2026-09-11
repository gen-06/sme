CREATE TABLE oauth2_signing_keys (
    id         VARCHAR(50) PRIMARY KEY,
    jwk_json   TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
