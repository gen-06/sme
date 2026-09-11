package com.creditscore.platform.identity.auth;

import com.creditscore.platform.identity.consumer.Consumer;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Authentication for a request bearing a valid OAuth2 access token. Authorities come
 * from the token's own "scope" claim (what was granted at issuance), not a live
 * Consumer.getScopes() lookup — an already-issued token isn't re-checked against
 * later scope changes (see the OAuth2 design spec's "known MVP limitations"). The
 * principal is still the real Consumer, matching ApiKeyAuthenticationToken, so
 * @AuthenticationPrincipal Consumer works identically for both auth methods.
 */
public class OAuth2ConsumerAuthenticationToken extends AbstractAuthenticationToken {

    private final Consumer consumer;

    public OAuth2ConsumerAuthenticationToken(Consumer consumer, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.consumer = consumer;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return consumer;
    }

    public Consumer getConsumer() {
        return consumer;
    }
}
