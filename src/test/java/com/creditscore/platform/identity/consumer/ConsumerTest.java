package com.creditscore.platform.identity.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsumerTest {

    @Test
    void forOAuth2ClientCreatesActiveConsumerWithNoApiKey() {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", "ops@acme.test",
                Set.of(ConsumerScope.SCORE_READ));

        assertThat(consumer.getName()).isEqualTo("Acme Lender");
        assertThat(consumer.getContactEmail()).isEqualTo("ops@acme.test");
        assertThat(consumer.getStatus()).isEqualTo(ConsumerStatus.ACTIVE);
        assertThat(consumer.getApiKeyHash()).isNull();
        assertThat(consumer.getScopes()).containsExactly(ConsumerScope.SCORE_READ);
    }

    @Test
    void oauthClientCredentialsAreSettableAfterConstruction() {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", null, Set.of(ConsumerScope.SCORE_READ));

        consumer.setOauthClientId("client_abc123");
        consumer.setOauthClientSecretHash("{bcrypt}$2a$10$examplehasheddata");

        assertThat(consumer.getOauthClientId()).isEqualTo("client_abc123");
        assertThat(consumer.getOauthClientSecretHash()).isEqualTo("{bcrypt}$2a$10$examplehasheddata");
    }

    @Test
    void suspendingAnActiveConsumerTransitionsStatus() {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", null, Set.of(ConsumerScope.SCORE_READ));

        consumer.transitionTo(ConsumerStatus.SUSPENDED);

        assertThat(consumer.getStatus()).isEqualTo(ConsumerStatus.SUSPENDED);
    }

    @Test
    void reactivatingASuspendedConsumerTransitionsBackToActive() {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", null, Set.of(ConsumerScope.SCORE_READ));
        consumer.transitionTo(ConsumerStatus.SUSPENDED);

        consumer.transitionTo(ConsumerStatus.ACTIVE);

        assertThat(consumer.getStatus()).isEqualTo(ConsumerStatus.ACTIVE);
    }

    @Test
    void revokingAConsumerTransitionsStatus() {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", null, Set.of(ConsumerScope.SCORE_READ));

        consumer.transitionTo(ConsumerStatus.REVOKED);

        assertThat(consumer.getStatus()).isEqualTo(ConsumerStatus.REVOKED);
    }

    @Test
    void transitioningToTheSameStatusIsANoOp() {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", null, Set.of(ConsumerScope.SCORE_READ));

        consumer.transitionTo(ConsumerStatus.ACTIVE);

        assertThat(consumer.getStatus()).isEqualTo(ConsumerStatus.ACTIVE);
    }

    @ParameterizedTest
    @EnumSource(value = ConsumerStatus.class)
    void revokedIsTerminalAndRejectsEveryTransitionOutOfIt(ConsumerStatus target) {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", null, Set.of(ConsumerScope.SCORE_READ));
        consumer.transitionTo(ConsumerStatus.REVOKED);

        if (target == ConsumerStatus.REVOKED) {
            consumer.transitionTo(target);
            assertThat(consumer.getStatus()).isEqualTo(ConsumerStatus.REVOKED);
        } else {
            assertThatThrownBy(() -> consumer.transitionTo(target))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(consumer.getStatus()).isEqualTo(ConsumerStatus.REVOKED);
        }
    }
}
