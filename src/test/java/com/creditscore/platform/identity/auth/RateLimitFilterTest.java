package com.creditscore.platform.identity.auth;

import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerScope;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class RateLimitFilterTest {

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/businesses");

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

    @Test
    void allowsRequestsUpToTheConfiguredLimitThenBlocks() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(2);
        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthenticationToken(consumerWithId(UUID.randomUUID())));
        FilterChain filterChain = mock(FilterChain.class);

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request, first, filterChain);
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request, second, filterChain);
        MockHttpServletResponse third = new MockHttpServletResponse();
        filter.doFilter(request, third, filterChain);

        verify(filterChain, times(1)).doFilter(request, first);
        verify(filterChain, times(1)).doFilter(request, second);
        verify(filterChain, never()).doFilter(request, third);
        assertThat(third.getStatus()).isEqualTo(429);
        assertThat(third.getHeader("Retry-After")).isNotNull();
        assertThat(third.getContentAsString()).contains("\"error\":\"rate_limited\"");
    }

    @Test
    void tracksSeparateBucketsPerConsumer() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1);
        FilterChain filterChain = mock(FilterChain.class);

        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthenticationToken(consumerWithId(UUID.randomUUID())));
        MockHttpServletResponse firstConsumerResponse = new MockHttpServletResponse();
        filter.doFilter(request, firstConsumerResponse, filterChain);

        SecurityContextHolder.getContext().setAuthentication(new ApiKeyAuthenticationToken(consumerWithId(UUID.randomUUID())));
        MockHttpServletResponse secondConsumerResponse = new MockHttpServletResponse();
        filter.doFilter(request, secondConsumerResponse, filterChain);

        verify(filterChain, times(1)).doFilter(request, firstConsumerResponse);
        verify(filterChain, times(1)).doFilter(request, secondConsumerResponse);
        assertThat(firstConsumerResponse.getStatus()).isNotEqualTo(429);
        assertThat(secondConsumerResponse.getStatus()).isNotEqualTo(429);
    }

    @Test
    void worksIdenticallyForAnOAuth2AuthenticatedRequest() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1);
        UUID consumerId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new OAuth2ConsumerAuthenticationToken(consumerWithId(consumerId),
                        List.of(new SimpleGrantedAuthority("SCORE_READ"))));
        FilterChain filterChain = mock(FilterChain.class);

        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(request, first, filterChain);
        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(request, second, filterChain);

        assertThat(first.getStatus()).isNotEqualTo(429);
        assertThat(second.getStatus()).isEqualTo(429);
    }

    @Test
    void passesThroughUnrateLimitedWhenThereIsNoAuthenticatedConsumer() throws Exception {
        RateLimitFilter filter = new RateLimitFilter(1);
        FilterChain filterChain = mock(FilterChain.class);

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, filterChain);
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        filter.doFilter(request, secondResponse, filterChain);

        verify(filterChain, times(1)).doFilter(request, response);
        verify(filterChain, times(1)).doFilter(request, secondResponse);
        assertThat(response.getStatus()).isNotEqualTo(429);
        assertThat(secondResponse.getStatus()).isNotEqualTo(429);
    }
}
