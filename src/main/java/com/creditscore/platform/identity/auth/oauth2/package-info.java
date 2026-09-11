/**
 * OAuth2 client-credentials support, coexisting with API-key auth (see
 * {@code com.creditscore.platform.identity.auth.ApiKeyAuthFilter}) rather than
 * replacing it. {@link com.creditscore.platform.identity.auth.oauth2.JpaRegisteredClientRepository}
 * adapts {@code Consumer} rows into Spring Authorization Server's {@code RegisteredClient}
 * — there is no separate client table. Token issuance stamps a {@code consumer_id}
 * claim ({@link com.creditscore.platform.identity.auth.oauth2.OAuth2ConsumerTokenCustomizer});
 * {@link com.creditscore.platform.identity.auth.oauth2.OAuth2ConsumerAuthenticationConverter}
 * reads it back on the resource-server side to build the same principal shape
 * {@code ApiKeyAuthenticationToken} uses. See
 * {@code docs/superpowers/specs/2026-09-11-oauth2-client-credentials-design.md}.
 */
package com.creditscore.platform.identity.auth.oauth2;
