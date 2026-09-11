package com.creditscore.platform.identity.auth.oauth2;

import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import com.creditscore.platform.identity.consumer.ConsumerScope;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuth2ConsumerAuthenticationConverterTest {

    private final ConsumerRepository consumerRepository = mock(ConsumerRepository.class);
    private final OAuth2ConsumerAuthenticationConverter converter =
            new OAuth2ConsumerAuthenticationConverter(consumerRepository);

    @Test
    void mapsScopeClaimToBareAuthoritiesAndLoadsConsumerAsPrincipal() {
        UUID consumerId = UUID.randomUUID();
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", "ops@acme.test", Set.of(ConsumerScope.SCORE_READ));
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.of(consumer));

        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claim("scope", "SCORE_READ TRANSACTION_READ")
                .claim("consumer_id", consumerId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getPrincipal()).isSameAs(consumer);
        assertThat(token.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("SCORE_READ", "TRANSACTION_READ");
    }

    @Test
    void missingConsumerIdClaimIsRejected() {
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claim("scope", "SCORE_READ")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void unknownConsumerIdIsRejected() {
        UUID consumerId = UUID.randomUUID();
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.empty());

        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claim("scope", "SCORE_READ")
                .claim("consumer_id", consumerId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(BadCredentialsException.class);
    }
}
