package com.creditscore.platform.identity.consumer;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

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
}
