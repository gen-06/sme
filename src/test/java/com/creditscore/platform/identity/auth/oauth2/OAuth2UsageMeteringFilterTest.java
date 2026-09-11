package com.creditscore.platform.identity.auth.oauth2;

import com.creditscore.platform.billing.UsageMeter;
import com.creditscore.platform.identity.auth.ApiKeyAuthenticationToken;
import com.creditscore.platform.identity.auth.OAuth2ConsumerAuthenticationToken;
import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import com.creditscore.platform.identity.consumer.ConsumerScope;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2UsageMeteringFilterTest {

    private final ConsumerRepository consumerRepository = mock(ConsumerRepository.class);
    private final UsageMeter usageMeter = mock(UsageMeter.class);

    // A real TransactionTemplate over a mocked PlatformTransactionManager: a plain
    // mock(TransactionTemplate.class) would never invoke the filter's lambda, so the
    // lastUsedAt re-fetch this filter exists for would silently not be exercised.
    private final TransactionTemplate transactionTemplate =
            new TransactionTemplate(mock(PlatformTransactionManager.class));

    private final OAuth2UsageMeteringFilter filter =
            new OAuth2UsageMeteringFilter(consumerRepository, usageMeter, transactionTemplate);

    private final MockHttpServletRequest request =
            new MockHttpServletRequest("GET", "/api/v1/businesses/abc/score");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static Consumer consumerWithId(UUID id) {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", "ops@acme.test",
                Set.of(ConsumerScope.SCORE_READ));
        ReflectionTestUtils.setField(consumer, "id", id);
        return consumer;
    }

    /**
     * The filter reads the SecurityContext in its finally block, after the rest of the
     * chain has run — so the chain, not the test setup, is what populates it. Modelling it
     * that way keeps the test honest about when the filter actually looks.
     */
    private FilterChain chainThatAuthenticatesAs(Authentication authentication, int status) {
        FilterChain filterChain = mock(FilterChain.class);
        try {
            org.mockito.Mockito.doAnswer(invocation -> {
                if (authentication != null) {
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
                response.setStatus(status);
                return null;
            }).when(filterChain).doFilter(request, response);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return filterChain;
    }

    @Test
    void recordsUsageAndRefreshesLastUsedAtForAnOAuth2AuthenticatedRequest() throws Exception {
        UUID consumerId = UUID.randomUUID();
        Consumer detachedConsumer = consumerWithId(consumerId);
        Consumer managedConsumer = consumerWithId(consumerId);
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.of(managedConsumer));

        OAuth2ConsumerAuthenticationToken authentication = new OAuth2ConsumerAuthenticationToken(
                detachedConsumer, List.of(new SimpleGrantedAuthority("SCORE_READ")));
        FilterChain filterChain = chainThatAuthenticatesAs(authentication, 200);

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verify(usageMeter, times(1))
                .record(consumerId, "/api/v1/businesses/abc/score", "GET", 200);

        // lastUsedAt is set on the RE-FETCHED entity, not on the detached one carried by
        // the authentication token — mutating the detached instance would silently not flush.
        verify(consumerRepository, times(1)).findById(consumerId);
        assertThat(managedConsumer.getLastUsedAt()).isNotNull();
        assertThat(detachedConsumer.getLastUsedAt()).isNull();
    }

    @Test
    void recordsTheActualResponseStatusForARejectedRequest() throws Exception {
        UUID consumerId = UUID.randomUUID();
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.of(consumerWithId(consumerId)));

        OAuth2ConsumerAuthenticationToken authentication = new OAuth2ConsumerAuthenticationToken(
                consumerWithId(consumerId), List.of(new SimpleGrantedAuthority("SCORE_READ")));

        filter.doFilter(request, response, chainThatAuthenticatesAs(authentication, 403));

        verify(usageMeter, times(1))
                .record(consumerId, "/api/v1/businesses/abc/score", "GET", 403);
    }

    /**
     * ApiKeyAuthFilter meters API-key traffic itself. If this filter also recorded it,
     * every API-key request would be billed twice.
     */
    @Test
    void recordsNothingForAnApiKeyAuthenticatedRequest() throws Exception {
        Consumer consumer = new Consumer("Legacy Consumer", null, "hash", "csk_1234",
                Set.of(ConsumerScope.SCORE_READ));
        ReflectionTestUtils.setField(consumer, "id", UUID.randomUUID());
        FilterChain filterChain = chainThatAuthenticatesAs(new ApiKeyAuthenticationToken(consumer), 200);

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verify(usageMeter, never()).record(any(), anyString(), anyString(), anyInt());
        verify(consumerRepository, never()).findById(any());
    }

    @Test
    void recordsNothingForAnUnauthenticatedRequest() throws Exception {
        FilterChain filterChain = chainThatAuthenticatesAs(null, 401);

        filter.doFilter(request, response, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verify(usageMeter, never()).record(any(), anyString(), anyString(), anyInt());
        verify(consumerRepository, never()).findById(any());
    }

    @Test
    void stillRecordsUsageWhenTheDownstreamChainThrows() {
        UUID consumerId = UUID.randomUUID();
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.of(consumerWithId(consumerId)));
        OAuth2ConsumerAuthenticationToken authentication = new OAuth2ConsumerAuthenticationToken(
                consumerWithId(consumerId), List.of(new SimpleGrantedAuthority("SCORE_READ")));

        FilterChain filterChain = mock(FilterChain.class);
        try {
            org.mockito.Mockito.doAnswer(invocation -> {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                response.setStatus(500);
                throw new IllegalStateException("downstream blew up");
            }).when(filterChain).doFilter(request, response);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> filter.doFilter(request, response, filterChain))
                .isInstanceOf(IllegalStateException.class);

        verify(usageMeter, times(1))
                .record(consumerId, "/api/v1/businesses/abc/score", "GET", 500);
    }
}
