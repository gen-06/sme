package com.creditscore.platform.identity.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

public class AdminTokenFilter extends OncePerRequestFilter {

    public static final String ADMIN_TOKEN_HEADER = "X-Platform-Admin-Token";

    private final String expectedToken;

    public AdminTokenFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String presentedToken = request.getHeader(ADMIN_TOKEN_HEADER);
        // A blank presented token is rejected before it ever reaches constantTimeEquals:
        // MessageDigest.isEqual returns true for two zero-length arrays, so an empty
        // header would otherwise authenticate against an empty/blank configured token
        // (a plausible env-var injection mistake).
        if (presentedToken != null && !presentedToken.isBlank()
                && constantTimeEquals(presentedToken, expectedToken)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "platform-admin", null, List.of(new SimpleGrantedAuthority("PLATFORM_ADMIN"))));
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
