package com.creditscore.platform.identity.consumer;

import com.creditscore.platform.identity.auth.oauth2.OAuth2ClientCredentialGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class ConsumerProvisioningService {

    private final ConsumerRepository consumerRepository;
    private final PasswordEncoder passwordEncoder;
    private final Duration secretRotationGracePeriod;

    public ConsumerProvisioningService(ConsumerRepository consumerRepository, PasswordEncoder passwordEncoder,
            @Value("${app.oauth2.secret-rotation-grace-period-hours}") long secretRotationGracePeriodHours) {
        this.consumerRepository = consumerRepository;
        this.passwordEncoder = passwordEncoder;
        this.secretRotationGracePeriod = Duration.ofHours(secretRotationGracePeriodHours);
    }

    @Transactional
    public ProvisionedConsumer provision(String name, String contactEmail, Set<ConsumerScope> scopes) {
        String clientId = OAuth2ClientCredentialGenerator.generateClientId();
        String rawClientSecret = OAuth2ClientCredentialGenerator.generateClientSecret();

        Consumer consumer = Consumer.forOAuth2Client(name, contactEmail, scopes);
        consumer.setOauthClientId(clientId);
        consumer.setOauthClientSecretHash(passwordEncoder.encode(rawClientSecret));

        Consumer saved = consumerRepository.save(consumer);
        return new ProvisionedConsumer(saved.getId(), clientId, rawClientSecret);
    }

    @Transactional
    public Consumer updateStatus(UUID consumerId, ConsumerStatus newStatus) {
        Consumer consumer = consumerRepository.findById(consumerId)
                .orElseThrow(() -> new NoSuchElementException("No consumer with id: " + consumerId));
        consumer.transitionTo(newStatus);
        return consumerRepository.save(consumer);
    }

    @Transactional
    public ProvisionedConsumer rotateSecret(UUID consumerId) {
        Consumer consumer = consumerRepository.findById(consumerId)
                .orElseThrow(() -> new NoSuchElementException("No consumer with id: " + consumerId));
        if (consumer.getOauthClientId() == null) {
            throw new IllegalArgumentException(
                    "Consumer " + consumerId + " has no OAuth2 client credentials to rotate");
        }

        String rawClientSecret = OAuth2ClientCredentialGenerator.generateClientSecret();
        consumer.rotateSecret(passwordEncoder.encode(rawClientSecret), secretRotationGracePeriod);

        Consumer saved = consumerRepository.save(consumer);
        return new ProvisionedConsumer(saved.getId(), saved.getOauthClientId(), rawClientSecret);
    }

    public record ProvisionedConsumer(UUID consumerId, String clientId, String clientSecret) {
    }
}
