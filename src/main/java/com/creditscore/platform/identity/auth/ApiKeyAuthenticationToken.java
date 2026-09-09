package com.creditscore.platform.identity.auth;

import com.creditscore.platform.identity.consumer.Consumer;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.stream.Collectors;

public class ApiKeyAuthenticationToken extends AbstractAuthenticationToken {

    private final Consumer consumer;

    public ApiKeyAuthenticationToken(Consumer consumer) {
        super(authorities(consumer));
        this.consumer = consumer;
        setAuthenticated(true);
    }

    private static Collection<GrantedAuthority> authorities(Consumer consumer) {
        return consumer.getScopes().stream()
                .map(scope -> (GrantedAuthority) new SimpleGrantedAuthority(scope.name()))
                .collect(Collectors.toSet());
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
