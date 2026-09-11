-- An OAuth2-only consumer (provisioned via POST /api/v1/admin/consumers) has no API
-- key at all. Existing rows are unaffected: every current row already has a non-null
-- value here, and Postgres's UNIQUE constraint tolerates multiple NULLs under
-- standard SQL null semantics — the same reasoning applies to the new constraint
-- below on oauth_client_id.
ALTER TABLE consumers ALTER COLUMN api_key_hash DROP NOT NULL;

-- oauth_client_id has existed since V2 but was never enforced unique or indexed —
-- it was unused until now. RegisteredClientRepository.findByClientId depends on
-- lookups here being unambiguous.
ALTER TABLE consumers ADD CONSTRAINT consumers_oauth_client_id_key UNIQUE (oauth_client_id);
