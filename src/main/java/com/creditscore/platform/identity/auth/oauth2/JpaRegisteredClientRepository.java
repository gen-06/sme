package com.creditscore.platform.identity.auth.oauth2;

import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
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

    public JpaRegisteredClientRepository(ConsumerRepository consumerRepository) {
        this.consumerRepository = consumerRepository;
    }

    @Override
    @Transactional
    public void save(RegisteredClient registeredClient) {
        Consumer consumer = consumerRepository.findById(UUID.fromString(registeredClient.getId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No consumer for registered client id: " + registeredClient.getId()));
        consumer.setOauthClientId(registeredClient.getClientId());
        consumer.setOauthClientSecretHash(registeredClient.getClientSecret());
    }

    @Override
    public RegisteredClient findById(String id) {
        return consumerRepository.findById(UUID.fromString(id)).map(this::toRegisteredClient).orElse(null);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return consumerRepository.findByOauthClientId(clientId).map(this::toRegisteredClient).orElse(null);
    }

    private RegisteredClient toRegisteredClient(Consumer consumer) {
        if (consumer.getOauthClientId() == null || consumer.getOauthClientSecretHash() == null) {
            return null;
        }
        return RegisteredClient.withId(consumer.getId().toString())
                .clientId(consumer.getOauthClientId())
                .clientSecret(consumer.getOauthClientSecretHash())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(scopes -> consumer.getScopes().forEach(scope -> scopes.add(scope.name())))
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .build())
                .build();
    }
}
