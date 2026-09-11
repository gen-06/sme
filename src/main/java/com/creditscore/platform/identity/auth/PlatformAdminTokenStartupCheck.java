package com.creditscore.platform.identity.auth;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Refuses to start the application when {@code app.admin.platform-admin-token} is still
 * the published local-dev default outside the {@code seed} profile.
 *
 * <p>The default value is printed verbatim in README.md and docker-compose.yml, so any
 * deployment that forgets to set {@code PLATFORM_ADMIN_TOKEN} would leave
 * {@code POST /api/v1/admin/consumers} — the platform's one privileged endpoint, able to
 * mint an OAuth2 client with arbitrary scopes — guarded by a credential anyone can read.
 *
 * <p>The check is gated on the {@code seed} profile (the same profile
 * {@link com.creditscore.platform.seed.SeedDataRunner} uses) rather than being
 * unconditional, because the local-dev workflow and the {@code docker compose --profile
 * full} demo both deliberately rely on the default. Failing during {@code @PostConstruct}
 * — not from an ApplicationRunner — means the failure happens while the context is still
 * refreshing, before the embedded server binds a port and serves anything.
 */
@Component
public class PlatformAdminTokenStartupCheck {

    static final String INSECURE_DEFAULT_TOKEN = "local-dev-admin-token";
    static final String SEED_PROFILE = "seed";

    private final String platformAdminToken;
    private final Environment environment;

    public PlatformAdminTokenStartupCheck(@Value("${app.admin.platform-admin-token}") String platformAdminToken,
                                           Environment environment) {
        this.platformAdminToken = platformAdminToken;
        this.environment = environment;
    }

    @PostConstruct
    void verifyAdminTokenIsNotThePublishedDefault() {
        if (!INSECURE_DEFAULT_TOKEN.equals(platformAdminToken)) {
            return;
        }
        boolean seedProfileActive = Arrays.asList(environment.getActiveProfiles()).contains(SEED_PROFILE);
        if (seedProfileActive) {
            return;
        }
        throw new IllegalStateException("""
                app.admin.platform-admin-token is still the published local-dev default \
                ('%s'), which is documented in README.md and docker-compose.yml and \
                therefore grants anyone who has read them full access to \
                POST /api/v1/admin/consumers. Set the PLATFORM_ADMIN_TOKEN environment \
                variable to a secret value, or run with the '%s' profile active if this \
                really is a local dev / demo instance."""
                .formatted(INSECURE_DEFAULT_TOKEN, SEED_PROFILE));
    }
}
