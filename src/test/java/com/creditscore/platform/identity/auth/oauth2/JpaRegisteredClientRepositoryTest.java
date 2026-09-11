package com.creditscore.platform.identity.auth.oauth2;

import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import com.creditscore.platform.identity.consumer.ConsumerScope;
import com.creditscore.platform.identity.consumer.ConsumerStatus;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaRegisteredClientRepositoryTest {

    private final ConsumerRepository consumerRepository = mock(ConsumerRepository.class);
    private final JpaRegisteredClientRepository repository = new JpaRegisteredClientRepository(consumerRepository);

    @Test
    void findByClientIdBuildsAClientCredentialsRegisteredClient() {
        UUID consumerId = UUID.randomUUID();
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", "ops@acme.test", Set.of(ConsumerScope.SCORE_READ));
        ReflectionTestUtils.setField(consumer, "id", consumerId);
        consumer.setOauthClientId("client_abc");
        consumer.setOauthClientSecretHash("{bcrypt}hashed");
        when(consumerRepository.findByOauthClientId("client_abc")).thenReturn(Optional.of(consumer));

        RegisteredClient registeredClient = repository.findByClientId("client_abc");

        assertThat(registeredClient).isNotNull();
        assertThat(registeredClient.getId()).isEqualTo(consumerId.toString());
        assertThat(registeredClient.getClientId()).isEqualTo("client_abc");
        assertThat(registeredClient.getClientSecret()).isEqualTo("{bcrypt}hashed");
        assertThat(registeredClient.getScopes()).containsExactly("SCORE_READ");
        assertThat(registeredClient.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
    }

    @Test
    void findByClientIdReturnsNullWhenNotFound() {
        when(consumerRepository.findByOauthClientId("missing")).thenReturn(Optional.empty());

        assertThat(repository.findByClientId("missing")).isNull();
    }

    @Test
    void findByClientIdReturnsNullForSuspendedConsumer() {
        UUID consumerId = UUID.randomUUID();
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", "ops@acme.test", Set.of(ConsumerScope.SCORE_READ));
        ReflectionTestUtils.setField(consumer, "id", consumerId);
        consumer.setOauthClientId("client_suspended");
        consumer.setOauthClientSecretHash("{bcrypt}hashed");
        ReflectionTestUtils.setField(consumer, "status", ConsumerStatus.SUSPENDED);
        when(consumerRepository.findByOauthClientId("client_suspended")).thenReturn(Optional.of(consumer));

        assertThat(repository.findByClientId("client_suspended")).isNull();
    }

    @Test
    void findByClientIdReturnsNullForRevokedConsumer() {
        UUID consumerId = UUID.randomUUID();
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", "ops@acme.test", Set.of(ConsumerScope.SCORE_READ));
        ReflectionTestUtils.setField(consumer, "id", consumerId);
        consumer.setOauthClientId("client_revoked");
        consumer.setOauthClientSecretHash("{bcrypt}hashed");
        ReflectionTestUtils.setField(consumer, "status", ConsumerStatus.REVOKED);
        when(consumerRepository.findByOauthClientId("client_revoked")).thenReturn(Optional.of(consumer));

        assertThat(repository.findByClientId("client_revoked")).isNull();
    }

    @Test
    void findByIdReturnsNullForApiKeyOnlyConsumer() {
        UUID consumerId = UUID.randomUUID();
        Consumer consumer = new Consumer("Legacy Consumer", null, "somehash", "csk_1234",
                Set.of(ConsumerScope.SCORE_READ));
        ReflectionTestUtils.setField(consumer, "id", consumerId);
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.of(consumer));

        assertThat(repository.findById(consumerId.toString())).isNull();
    }
}
