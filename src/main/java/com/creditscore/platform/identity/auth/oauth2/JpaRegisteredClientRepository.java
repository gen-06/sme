package com.creditscore.platform.identity.auth.oauth2;

import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import com.creditscore.platform.identity.consumer.ConsumerStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapts Consumer rows to RegisteredClient — there is no separate client table. A
 * Consumer provisioned with OAuth2 credentials (ConsumerProvisioningService) IS the
 * registered client, keyed by the oauth_client_id/oauth_client_secret_hash columns it
 * already carried unused. RegisteredClient.getId() is always the Consumer's own UUID,
 * which OAuth2ConsumerTokenCustomizer relies on to stamp the consumer_id claim without
 * a second lookup at token-issuance time.
 */
@Component
public class JpaRegisteredClientRepository implements RegisteredClientRepository {

    private final ConsumerRepository consumerRepository;
    private final Duration accessTokenTimeToLive;

    public JpaRegisteredClientRepository(ConsumerRepository consumerRepository,
                                          @Value("${app.oauth2.access-token-ttl-minutes}") long accessTokenTtlMinutes) {
        this.consumerRepository = consumerRepository;
        this.accessTokenTimeToLive = Duration.ofMinutes(accessTokenTtlMinutes);
    }

    @Override
    @Transactional
    public void save(RegisteredClient registeredClient) {
        Consumer consumer = parseConsumerId(registeredClient.getId())
                .flatMap(consumerRepository::findById)
                .orElseThrow(() -> new IllegalArgumentException(
                        "No consumer for registered client id: " + registeredClient.getId()));
        consumer.setOauthClientId(registeredClient.getClientId());
        consumer.setOauthClientSecretHash(registeredClient.getClientSecret());
    }

    @Override
    public RegisteredClient findById(String id) {
        // RegisteredClient ids are always a Consumer's own UUID, so a malformed id can only
        // mean "no such client". Collapsing the parse failure into the interface's existing
        // not-found contract keeps an IllegalArgumentException from escaping as a 500.
        return parseConsumerId(id).flatMap(consumerRepository::findById)
                .map(this::toRegisteredClient).orElse(null);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return consumerRepository.findByOauthClientId(clientId).map(this::toRegisteredClient).orElse(null);
    }

    private RegisteredClient toRegisteredClient(Consumer consumer) {
        if (consumer.getOauthClientId() == null || consumer.getOauthClientSecretHash() == null) {
            return null;
        }
        // A suspended/revoked Consumer must stop minting new tokens immediately (per
        // the design spec's "known MVP limitations"). Spring Authorization Server
        // calls findByClientId to authenticate the client during /oauth2/token, so
        // returning null here — the same signal used for "no oauth credentials" —
        // is what actually blocks new issuance. Already-issued tokens are validated
        // by OAuth2ConsumerAuthenticationConverter, which deliberately does not
        // re-check status, so they intentionally remain valid until they expire.
        if (consumer.getStatus() != ConsumerStatus.ACTIVE) {
            return null;
        }
        return RegisteredClient.withId(consumer.getId().toString())
                .clientId(consumer.getOauthClientId())
                .clientSecret(consumer.getOauthClientSecretHash())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(scopes -> consumer.getScopes().forEach(scope -> scopes.add(scope.name())))
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(this.accessTokenTimeToLive)
                        .build())
                .build();
    }

    private static Optional<UUID> parseConsumerId(String id) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException notAUuid) {
            return Optional.empty();
        }
    }
}
