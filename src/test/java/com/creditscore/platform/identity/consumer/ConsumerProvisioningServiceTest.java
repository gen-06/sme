package com.creditscore.platform.identity.consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsumerProvisioningServiceTest {

    private final ConsumerRepository consumerRepository = mock(ConsumerRepository.class);
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final ConsumerProvisioningService service =
            new ConsumerProvisioningService(consumerRepository, passwordEncoder);

    @Test
    void provisionGeneratesClientCredentialsAndStoresOnlyTheHash() {
        when(consumerRepository.save(any(Consumer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.provision("Acme Lender", "ops@acme.test", Set.of(ConsumerScope.SCORE_READ));

        assertThat(result.clientId()).startsWith("client_");
        assertThat(result.clientSecret()).startsWith("secret_");

        ArgumentCaptor<Consumer> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(consumerRepository).save(captor.capture());
        Consumer saved = captor.getValue();

        assertThat(saved.getOauthClientId()).isEqualTo(result.clientId());
        assertThat(saved.getOauthClientSecretHash()).isNotEqualTo(result.clientSecret());
        assertThat(passwordEncoder.matches(result.clientSecret(), saved.getOauthClientSecretHash())).isTrue();
        assertThat(saved.getScopes()).containsExactly(ConsumerScope.SCORE_READ);
    }

    @Test
    void updateStatusTransitionsAndSavesTheConsumer() {
        UUID consumerId = UUID.randomUUID();
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", "ops@acme.test", Set.of(ConsumerScope.SCORE_READ));
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.of(consumer));
        when(consumerRepository.save(any(Consumer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Consumer result = service.updateStatus(consumerId, ConsumerStatus.SUSPENDED);

        assertThat(result.getStatus()).isEqualTo(ConsumerStatus.SUSPENDED);
        verify(consumerRepository).save(consumer);
    }

    @Test
    void updateStatusForAnUnknownConsumerThrowsNoSuchElement() {
        UUID consumerId = UUID.randomUUID();
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(consumerId, ConsumerStatus.SUSPENDED))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void updateStatusRejectsATransitionOutOfRevoked() {
        UUID consumerId = UUID.randomUUID();
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", "ops@acme.test", Set.of(ConsumerScope.SCORE_READ));
        consumer.transitionTo(ConsumerStatus.REVOKED);
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.of(consumer));

        assertThatThrownBy(() -> service.updateStatus(consumerId, ConsumerStatus.ACTIVE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
