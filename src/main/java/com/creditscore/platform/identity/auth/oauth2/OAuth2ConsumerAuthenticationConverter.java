package com.creditscore.platform.identity.auth.oauth2;

import com.creditscore.platform.identity.auth.OAuth2ConsumerAuthenticationToken;
import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.UUID;

/**
 * Two independent jobs, matching the design spec: (1) map the JWT's "scope" claim to
 * bare authority strings — delegating to Spring's own JwtGrantedAuthoritiesConverter
 * with an empty prefix, rather than hand-parsing, since that converter already
 * handles both the space-delimited-string and collection claim shapes correctly;
 * (2) load the real Consumer (by the consumer_id claim OAuth2ConsumerTokenCustomizer
 * stamps at issuance) so the resulting principal matches ApiKeyAuthenticationToken's
 * shape exactly.
 */
public class OAuth2ConsumerAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final ConsumerRepository consumerRepository;
    private final JwtGrantedAuthoritiesConverter scopeAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();

    public OAuth2ConsumerAuthenticationConverter(ConsumerRepository consumerRepository) {
        this.consumerRepository = consumerRepository;
        this.scopeAuthoritiesConverter.setAuthorityPrefix("");
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = scopeAuthoritiesConverter.convert(jwt);

        String consumerId = jwt.getClaimAsString("consumer_id");
        if (consumerId == null) {
            throw new BadCredentialsException("Token is missing the consumer_id claim");
        }

        UUID parsedConsumerId;
        try {
            parsedConsumerId = UUID.fromString(consumerId);
        } catch (IllegalArgumentException notAUuid) {
            // Unreachable today (the claim is signed by this app's own key and always
            // written from consumer.getId().toString()), but IllegalArgumentException is not
            // an AuthenticationException, so it would escape as an uncaught 500 rather than
            // a 401. Translating it keeps all three "unresolvable identity" paths in this
            // class failing closed the same way.
            throw new BadCredentialsException("Token carries a malformed consumer_id claim: " + consumerId);
        }

        Consumer consumer = consumerRepository.findById(parsedConsumerId)
                .orElseThrow(() -> new BadCredentialsException("Token references an unknown consumer: " + consumerId));

        return new OAuth2ConsumerAuthenticationToken(consumer, authorities);
    }
}
