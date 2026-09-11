package com.creditscore.platform.config;

import com.creditscore.platform.billing.UsageMeter;
import com.creditscore.platform.identity.auth.AdminTokenFilter;
import com.creditscore.platform.identity.auth.ApiKeyAuthFilter;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(HttpSecurity http,
            @Value("${app.admin.platform-admin-token}") String platformAdminToken) throws Exception {
        AdminTokenFilter adminTokenFilter = new AdminTokenFilter(platformAdminToken);

        http
                .securityMatcher("/api/v1/admin/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(adminTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("PLATFORM_ADMIN"))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(this::unauthorized)
                        .accessDeniedHandler(this::forbidden));

        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain apiFilterChain(HttpSecurity http, ConsumerRepository consumerRepository,
                                               PlatformTransactionManager transactionManager,
                                               UsageMeter usageMeter) throws Exception {
        ApiKeyAuthFilter apiKeyAuthFilter =
                new ApiKeyAuthFilter(consumerRepository, new TransactionTemplate(transactionManager), usageMeter);

        http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(this::unauthorized)
                        .accessDeniedHandler(this::forbidden));

        return http.build();
    }

    @Bean
    @Order(4)
    public SecurityFilterChain publicFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html", "/actuator/health")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    private void unauthorized(jakarta.servlet.http.HttpServletRequest request,
                               jakarta.servlet.http.HttpServletResponse response,
                               Exception exception) throws IOException {
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Missing or invalid credentials\"}");
    }

    private void forbidden(jakarta.servlet.http.HttpServletRequest request,
                            jakarta.servlet.http.HttpServletResponse response,
                            org.springframework.security.access.AccessDeniedException exception) throws IOException {
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"forbidden\",\"message\":\"Consumer lacks required scope\"}");
    }
}
