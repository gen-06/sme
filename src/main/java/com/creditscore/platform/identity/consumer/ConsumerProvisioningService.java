package com.creditscore.platform.identity.consumer;

import com.creditscore.platform.identity.auth.oauth2.OAuth2ClientCredentialGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class ConsumerProvisioningService {

    private final ConsumerRepository consumerRepository;
    private final PasswordEncoder passwordEncoder;

    public ConsumerProvisioningService(ConsumerRepository consumerRepository, PasswordEncoder passwordEncoder) {
        this.consumerRepository = consumerRepository;
        this.passwordEncoder = passwordEncoder;
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

    public record ProvisionedConsumer(UUID consumerId, String clientId, String clientSecret) {
    }
}
