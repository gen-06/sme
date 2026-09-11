package com.creditscore.platform.identity.auth.oauth2;

import com.creditscore.platform.billing.UsageMeter;
import com.creditscore.platform.identity.auth.OAuth2ConsumerAuthenticationToken;
import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * ApiKeyAuthFilter records usage and lastUsedAt itself, from its own credential
 * lookup, so it never sees OAuth2-authenticated requests (no X-API-Key header). This
 * filter closes that gap for the bearer-token path: it wraps the rest of the chain,
 * and in its finally block — after authentication has resolved and the response
 * status is final — checks whether the request ended up authenticated via OAuth2 and
 * records the same usage/lastUsedAt bookkeeping ApiKeyAuthFilter does for API keys.
 *
 * <p>The Consumer carried by OAuth2ConsumerAuthenticationToken was loaded by
 * OAuth2ConsumerAuthenticationConverter during JWT authentication, in a transaction
 * that has already closed by the time this filter's finally block runs — it is a
 * detached entity. Mutating it directly and relying on Hibernate dirty-checking would
 * silently do nothing, unlike ApiKeyAuthFilter's update (which mutates an entity it
 * fetched itself, inside its own still-open transaction). So this filter re-fetches
 * the Consumer by id inside its own transaction before setting lastUsedAt, matching
 * the persistence semantics ApiKeyAuthFilter relies on rather than assuming the
 * detached instance's mutation will flush.
 *
 * <p>Positioned to run before ApiKeyAuthFilter in apiFilterChain so its doFilter()
 * call wraps every downstream filter (both ApiKeyAuthFilter and the resource server's
 * bearer-token authentication), matching how ApiKeyAuthFilter itself wraps the rest
 * of the chain today.
 */
public class OAuth2UsageMeteringFilter extends OncePerRequestFilter {

    private final ConsumerRepository consumerRepository;
    private final UsageMeter usageMeter;
    private final TransactionTemplate transactionTemplate;

    public OAuth2UsageMeteringFilter(ConsumerRepository consumerRepository, UsageMeter usageMeter,
                                      TransactionTemplate transactionTemplate) {
        this.consumerRepository = consumerRepository;
        this.usageMeter = usageMeter;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication instanceof OAuth2ConsumerAuthenticationToken oauthToken) {
                Consumer consumer = oauthToken.getConsumer();

                transactionTemplate.executeWithoutResult(status ->
                        consumerRepository.findById(consumer.getId())
                                .ifPresent(managed -> managed.setLastUsedAt(Instant.now())));

                usageMeter.record(consumer.getId(), request.getRequestURI(), request.getMethod(),
                        response.getStatus());
            }
        }
    }
}
