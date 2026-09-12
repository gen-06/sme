package com.creditscore.platform.identity.auth;

import com.creditscore.platform.identity.consumer.Consumer;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Positioned in {@code SecurityConfig.apiFilterChain} with
 * {@code addFilterAfter(rateLimitFilter, AuthorizationFilter.class)}, so it only ever
 * runs for a request that has already passed authentication and authorization — never
 * for one about to fail with 401/403 anyway. Both {@code ApiKeyAuthenticationToken} and
 * {@code OAuth2ConsumerAuthenticationToken} return the real {@code Consumer} from
 * {@code getPrincipal()}, so a single {@code instanceof} check covers either credential
 * type uniformly without needing a shared marker interface.
 *
 * <p>One {@link Bucket} per consumer, held in an in-memory map for this instance's
 * lifetime — never evicted. Correct and cheap for this MVP's small, manually
 * provisioned consumer set; a real-scale deployment would need a bounded/expiring
 * cache (e.g. Caffeine) instead, and a multi-instance deployment enforces this limit
 * per instance, not globally — see README's Known limitations.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();
    private final int requestsPerMinute;

    public RateLimitFilter(int requestsPerMinute) {
        this.requestsPerMinute = requestsPerMinute;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null && authentication.getPrincipal() instanceof Consumer consumer) {
            Bucket bucket = buckets.computeIfAbsent(consumer.getId(), id -> newBucket());
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

            if (!probe.isConsumed()) {
                long retryAfterSeconds = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write(
                        "{\"error\":\"rate_limited\",\"message\":\"Too many requests, retry after "
                                + retryAfterSeconds + " seconds\"}");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(requestsPerMinute)
                        .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                        .build())
                .build();
    }
}
