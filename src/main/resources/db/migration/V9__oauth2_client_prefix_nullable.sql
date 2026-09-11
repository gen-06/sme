-- V8 relaxed api_key_hash to nullable for OAuth2-only consumers but missed
-- api_key_prefix, which is also NOT NULL (V2) and is never set by
-- Consumer.forOAuth2Client. Same reasoning as V8: existing rows are
-- unaffected, this column has no UNIQUE constraint so no null-semantics
-- concern applies.
ALTER TABLE consumers ALTER COLUMN api_key_prefix DROP NOT NULL;
