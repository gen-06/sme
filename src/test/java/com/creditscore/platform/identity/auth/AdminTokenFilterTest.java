package com.creditscore.platform.identity.auth;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AdminTokenFilterTest {

    private static final String EXPECTED_TOKEN = "s3cret-admin-token";

    private final AdminTokenFilter filter = new AdminTokenFilter(EXPECTED_TOKEN);
    private final FilterChain filterChain = mock(FilterChain.class);
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/consumers");
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @BeforeEach
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void correctTokenAuthenticatesWithThePlatformAdminAuthority() throws Exception {
        request.addHeader(AdminTokenFilter.ADMIN_TOKEN_HEADER, EXPECTED_TOKEN);

        filter.doFilter(request, response, filterChain);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.getPrincipal()).isEqualTo("platform-admin");
        assertThat(authentication.getAuthorities())
                .extracting(Object::toString)
                .containsExactly("PLATFORM_ADMIN");
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void wrongTokenSetsNoAuthentication() throws Exception {
        request.addHeader(AdminTokenFilter.ADMIN_TOKEN_HEADER, "not-the-token");

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void missingHeaderSetsNoAuthentication() throws Exception {
        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void emptyHeaderSetsNoAuthentication() throws Exception {
        request.addHeader(AdminTokenFilter.ADMIN_TOKEN_HEADER, "");

        filter.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    /**
     * Regression test for the real bypass: {@code MessageDigest.isEqual} returns true for
     * two zero-length arrays, so before the blank-token guard an empty
     * {@code X-Platform-Admin-Token} header authenticated successfully whenever
     * {@code PLATFORM_ADMIN_TOKEN} was itself set to an empty string — a plausible
     * env-var injection mistake. The presented token must be rejected before it ever
     * reaches the comparison.
     */
    @Test
    void emptyHeaderDoesNotAuthenticateAgainstAnEmptyConfiguredToken() throws Exception {
        AdminTokenFilter filterWithEmptyExpectedToken = new AdminTokenFilter("");
        request.addHeader(AdminTokenFilter.ADMIN_TOKEN_HEADER, "");

        filterWithEmptyExpectedToken.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }

    @Test
    void whitespaceOnlyHeaderDoesNotAuthenticateAgainstABlankConfiguredToken() throws Exception {
        AdminTokenFilter filterWithBlankExpectedToken = new AdminTokenFilter("   ");
        request.addHeader(AdminTokenFilter.ADMIN_TOKEN_HEADER, "   ");

        filterWithBlankExpectedToken.doFilter(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain, times(1)).doFilter(request, response);
    }
}
