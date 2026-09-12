package com.creditscore.platform.identity.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
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

    @Test
    void effectiveSecretIsJustThePrimaryHashWhenNoRotationIsInProgress() {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", null, Set.of(ConsumerScope.SCORE_READ));
        consumer.setOauthClientSecretHash("{bcrypt}primaryhash");

        assertThat(consumer.getEffectiveOauthClientSecret()).isEqualTo("{bcrypt}primaryhash");
    }

    @Test
    void effectiveSecretIsNullWhenThePrimaryHashIsNullEvenIfAPreviousHashSomehowExists() {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", null, Set.of(ConsumerScope.SCORE_READ));
        // Not reachable via any real write path today (rotateSecret always moves a
        // real primary into previous) — this pins the defensive guard so a future
        // change can't silently reintroduce a "null|<hash>" composite string.
        ReflectionTestUtils.setField(consumer, "oauthClientSecretHashPrevious", "{bcrypt}orphanedprevious");
        ReflectionTestUtils.setField(consumer, "oauthClientSecretPreviousExpiresAt",
                Instant.now().plusSeconds(3600));

        assertThat(consumer.getEffectiveOauthClientSecret()).isNull();
    }

    @Test
    void rotatingTheSecretMovesTheOldHashToPreviousWithAnExpiry() {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", null, Set.of(ConsumerScope.SCORE_READ));
        consumer.setOauthClientSecretHash("{bcrypt}oldhash");

        consumer.rotateSecret("{bcrypt}newhash", Duration.ofHours(24));

        assertThat(consumer.getOauthClientSecretHash()).isEqualTo("{bcrypt}newhash");
        assertThat(consumer.getEffectiveOauthClientSecret())
                .isEqualTo("{bcrypt}newhash|{bcrypt}oldhash");
    }

    @Test
    void effectiveSecretDropsThePreviousHashOnceItsGracePeriodExpires() {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", null, Set.of(ConsumerScope.SCORE_READ));
        consumer.setOauthClientSecretHash("{bcrypt}oldhash");
        consumer.rotateSecret("{bcrypt}newhash", Duration.ofHours(24));
        ReflectionTestUtils.setField(consumer, "oauthClientSecretPreviousExpiresAt",
                Instant.now().minusSeconds(1));

        assertThat(consumer.getEffectiveOauthClientSecret()).isEqualTo("{bcrypt}newhash");
    }

    @Test
    void rotatingTwiceBeforeTheFirstGracePeriodExpiresOverwritesThePreviousSlot() {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", null, Set.of(ConsumerScope.SCORE_READ));
        consumer.setOauthClientSecretHash("{bcrypt}v1");
        consumer.rotateSecret("{bcrypt}v2", Duration.ofHours(24));

        consumer.rotateSecret("{bcrypt}v3", Duration.ofHours(24));

        assertThat(consumer.getOauthClientSecretHash()).isEqualTo("{bcrypt}v3");
        assertThat(consumer.getEffectiveOauthClientSecret()).isEqualTo("{bcrypt}v3|{bcrypt}v2");
    }
}
