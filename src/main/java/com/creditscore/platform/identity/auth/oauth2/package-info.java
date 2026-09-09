/**
 * Reserved for the OAuth2 client-credentials flow that will replace API-key auth
 * once the MVP is validated (see the product brief's "path from MVP to production").
 * The swap point is {@code SecurityConfig}: controllers reference only
 * {@code Authentication}, never the raw API key, so replacing {@code ApiKeyAuthFilter}
 * with {@code .oauth2ResourceServer(...)} here will not require any controller changes.
 */
package com.creditscore.platform.identity.auth.oauth2;
