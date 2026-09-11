package com.creditscore.platform.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards SecurityConfig.SUPPORTED_AUTHORIZATION_SERVER_ENDPOINTS against drifting out
 * of sync with AuthorizationServerSettings' defaults — the hardcoded array and the
 * settings bean are two independent sources of truth for the same endpoint paths, and
 * nothing else in this codebase checks they still agree.
 */
class SecurityConfigEndpointsTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    @Test
    void supportedEndpointsStayInSyncWithAuthorizationServerSettingsDefaults() {
        AuthorizationServerSettings settings = securityConfig.authorizationServerSettings();

        assertThat(SecurityConfig.SUPPORTED_AUTHORIZATION_SERVER_ENDPOINTS)
                .contains(settings.getTokenEndpoint(), settings.getJwkSetEndpoint());
    }

    @Test
    void metadataEndpointIsTheFixedWellKnownPath() {
        assertThat(SecurityConfig.SUPPORTED_AUTHORIZATION_SERVER_ENDPOINTS)
                .contains("/.well-known/oauth-authorization-server");
    }
}
