package com.creditscore.platform.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Registered via {@link LoggingConfig} as a plain {@code FilterRegistrationBean} at
 * {@code Ordered.HIGHEST_PRECEDENCE} — NOT attached to any single
 * {@code SecurityFilterChain} in {@code SecurityConfig}. A request only ever enters one
 * of that class's seven chains (chosen by first-matching {@code securityMatcher}), so a
 * filter added to only one of them would miss every request the others (or none of
 * them) handle. Registering as a plain servlet filter instead means this runs for
 * literally every request that reaches the container, before Spring Security's own
 * filter chain machinery.
 *
 * <p>Also logs one access-log line per request: this codebase otherwise has almost no
 * per-request log statements (only startup/seed logging and one rare race-condition
 * log), so without this line the request-ID correlation this filter exists for would
 * have nothing to actually correlate for a typical request. Logged before
 * {@code MDC.remove} so the line itself carries the request ID like any other log
 * statement made during this request would. Skipped for {@code /actuator/**}: the
 * Docker healthcheck in {@code docker-compose.yml} hits {@code /actuator/health} every
 * 10 seconds forever, and logging that would produce roughly 8,600 identical lines a
 * day in any real deployment — pure noise that costs real money in a hosted log
 * aggregator. The request still gets an ID and a response header either way; only the
 * access-log line is skipped.
 */
public class RequestIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final String MDC_KEY = "requestId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        String requestId = (incoming != null && !incoming.isBlank()) ? incoming : UUID.randomUUID().toString();

        MDC.put(MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (!request.getRequestURI().startsWith("/actuator/")) {
                long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
                log.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(),
                        durationMs);
            }
            MDC.remove(MDC_KEY);
        }
    }
}
