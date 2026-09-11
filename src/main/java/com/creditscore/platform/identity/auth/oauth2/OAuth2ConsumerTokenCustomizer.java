package com.creditscore.platform.identity.auth.oauth2;

import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * Spring Authorization Server auto-detects any OAuth2TokenCustomizer<JwtEncodingContext>
 * bean in context and applies it to every issued access token. RegisteredClient.getId()
 * is always the Consumer's own UUID (see JpaRegisteredClientRepository), so this needs
 * no database lookup — it just re-exposes an id Spring Authorization Server already
 * resolved as an explicit claim OAuth2ConsumerAuthenticationConverter can read later.
 */
@Component
public class OAuth2ConsumerTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    @Override
    public void customize(JwtEncodingContext context) {
        context.getClaims().claim("consumer_id", context.getRegisteredClient().getId());
    }
}
