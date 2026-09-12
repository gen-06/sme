-- Matches oauth_client_secret_hash's own VARCHAR(255) — both columns ever hold exactly
-- one encoded secret hash; the two are only ever combined in memory (Consumer.getEffectiveOauthClientSecret),
-- never persisted as a single composite value.
ALTER TABLE consumers
    ADD COLUMN oauth_client_secret_hash_previous VARCHAR(255),
    ADD COLUMN oauth_client_secret_previous_expires_at TIMESTAMPTZ;
