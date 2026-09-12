package com.creditscore.platform.identity.auth.oauth2;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Spring Authorization Server's JDBC store has no built-in eviction — moving it off
 * heap (see JdbcOAuth2AuthorizationService in SecurityConfig) stopped it from growing
 * without bound only if something deletes expired rows. This platform only ever
 * issues client_credentials tokens, so authorization_code/refresh_token/device_code
 * columns are always NULL and access_token_expires_at alone is a complete predicate
 * for "this row is stale." The same premise cuts the other way: a row with a NULL
 * access_token_expires_at would never be matched by this predicate and would
 * accumulate silently — also unreachable today, since every row this platform
 * issues has a non-null access token.
 */
@Component
public class OAuth2AuthorizationCleanupJob {

    private final JdbcTemplate jdbcTemplate;

    public OAuth2AuthorizationCleanupJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "${app.oauth2.authorization-cleanup-cron}")
    public void deleteExpiredAuthorizations() {
        jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE access_token_expires_at < ?",
                Timestamp.from(Instant.now()));
    }
}
