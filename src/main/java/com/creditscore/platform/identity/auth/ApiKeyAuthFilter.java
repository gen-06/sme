package com.creditscore.platform.identity.auth;

import com.creditscore.platform.billing.UsageMeter;
import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import com.creditscore.platform.identity.consumer.ConsumerStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.Optional;

public class ApiKeyAuthFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private final ConsumerRepository consumerRepository;
    private final TransactionTemplate transactionTemplate;
    private final UsageMeter usageMeter;

    public ApiKeyAuthFilter(ConsumerRepository consumerRepository, TransactionTemplate transactionTemplate,
                             UsageMeter usageMeter) {
        this.consumerRepository = consumerRepository;
        this.transactionTemplate = transactionTemplate;
        this.usageMeter = usageMeter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String rawKey = request.getHeader(API_KEY_HEADER);
        Consumer authenticatedConsumer = null;

        if (rawKey != null && !rawKey.isBlank()) {
            String hash = ApiKeyHasher.hash(rawKey);

            // Wrapped in a transaction so the EAGER-fetched Consumer.scopes collection
            // and the lastUsedAt update both happen inside an open persistence context.
            Optional<Consumer> maybeConsumer = transactionTemplate.execute(status -> {
                Optional<Consumer> found = consumerRepository.findByApiKeyHash(hash);
                found.filter(c -> c.getStatus() == ConsumerStatus.ACTIVE)
                        .ifPresent(c -> c.setLastUsedAt(Instant.now()));
                return found.filter(c -> c.getStatus() == ConsumerStatus.ACTIVE);
            });

            if (maybeConsumer.isPresent()) {
                authenticatedConsumer = maybeConsumer.get();
                SecurityContextHolder.getContext()
                        .setAuthentication(new ApiKeyAuthenticationToken(authenticatedConsumer));
            }
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            if (authenticatedConsumer != null) {
                usageMeter.record(authenticatedConsumer.getId(), request.getRequestURI(), request.getMethod(),
                        response.getStatus());
            }
        }
    }
}
