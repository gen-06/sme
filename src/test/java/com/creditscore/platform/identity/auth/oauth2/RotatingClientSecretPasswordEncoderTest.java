package com.creditscore.platform.identity.auth.oauth2;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class RotatingClientSecretPasswordEncoderTest {

    private final PasswordEncoder delegate = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final RotatingClientSecretPasswordEncoder encoder = new RotatingClientSecretPasswordEncoder(delegate);

    @Test
    void encodeDelegatesStraightThroughAndProducesARealDecodableHash() {
        String encoded = encoder.encode("raw-secret");

        assertThat(delegate.matches("raw-secret", encoded)).isTrue();
    }

    @Test
    void matchesASingleHashWithNoRotationInProgress() {
        String hash = delegate.encode("current-secret");

        assertThat(encoder.matches("current-secret", hash)).isTrue();
        assertThat(encoder.matches("wrong-secret", hash)).isFalse();
    }

    @Test
    void matchesEitherHalfOfACompositeTwoSecretString() {
        String primaryHash = delegate.encode("new-secret");
        String previousHash = delegate.encode("old-secret");
        String composite = primaryHash + "|" + previousHash;

        assertThat(encoder.matches("new-secret", composite)).isTrue();
        assertThat(encoder.matches("old-secret", composite)).isTrue();
        assertThat(encoder.matches("neither-secret", composite)).isFalse();
    }

    @Test
    void upgradeEncodingAlwaysReturnsFalse() {
        String hash = delegate.encode("current-secret");
        String composite = hash + "|" + delegate.encode("old-secret");

        assertThat(encoder.upgradeEncoding(hash)).isFalse();
        assertThat(encoder.upgradeEncoding(composite)).isFalse();
    }
}
