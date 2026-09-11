package com.creditscore.platform.config;

import com.creditscore.platform.billing.UsageMeter;
import com.creditscore.platform.identity.auth.AdminTokenFilter;
import com.creditscore.platform.identity.auth.ApiKeyAuthFilter;
import com.creditscore.platform.identity.auth.oauth2.OAuth2ConsumerAuthenticationConverter;
import com.creditscore.platform.identity.auth.oauth2.OAuth2UsageMeteringFilter;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.UUID;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * The only Authorization Server endpoints this platform implements. Must stay in sync
     * with {@link AuthorizationServerSettings}' defaults for the token, JWK set and
     * metadata endpoints.
     */
    static final String[] SUPPORTED_AUTHORIZATION_SERVER_ENDPOINTS = {
            "/oauth2/token", "/oauth2/jwks", "/.well-known/oauth-authorization-server"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource() throws JOSEException {
        RSAKey rsaKey = new RSAKeyGenerator(2048)
                .keyID(UUID.randomUUID().toString())
                .generate();
        JWKSet jwkSet = new JWKSet(rsaKey);
        return new ImmutableJWKSet<>(jwkSet);
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
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

    /**
     * Deliberately does NOT use {@code OAuth2AuthorizationServerConfiguration.applyDefaultSecurity},
     * which sets {@code securityMatcher(configurer.getEndpointsMatcher())} — the FULL Spring
     * Authorization Server surface: {@code /oauth2/authorize}, {@code /oauth2/revoke},
     * {@code /oauth2/introspect}, {@code /oauth2/device_authorization},
     * {@code /oauth2/device_verification}. This platform implements only the
     * client-credentials grant, so it needs exactly three of those endpoints and has never
     * reviewed the rest.
     *
     * <p>Narrowing the {@code securityMatcher} (rather than leaving the matcher wide and
     * adding {@code anyRequest().denyAll()}) is what actually closes the surface. SAS
     * registers its endpoint filters at two different positions: the token, introspection,
     * revocation and device-authorization filters go in with
     * {@code addFilterAfter(..., AuthorizationFilter.class)} and so ARE governed by
     * {@code authorizeHttpRequests}, but the authorization-endpoint and
     * device-verification filters go in with
     * {@code addFilterBefore(..., AbstractPreAuthenticatedProcessingFilter.class)} and
     * therefore run BEFORE {@code AuthorizationFilter} ever evaluates a rule. A
     * {@code denyAll()} rule consequently cannot stop {@code GET /oauth2/authorize} from
     * reaching SAS's internal authorization-code validation, which fails with an
     * unhandled 500 for this app's clients (they declare no {@code redirectUris}). Because
     * this chain no longer matches those URIs at all, the filters simply never run for
     * them; {@link #oauth2DisabledEndpointsFilterChain} then denies them explicitly.
     *
     * <p>{@code permitAll} here is not "unauthenticated access": the token endpoint
     * authenticates the client itself via HTTP Basic in {@code OAuth2ClientAuthenticationFilter},
     * which is registered before {@code AuthorizationFilter} and so runs regardless of this
     * rule. {@code /oauth2/jwks} and the metadata document are public by specification.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain authorizationServerFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();

        http
                .securityMatcher(SUPPORTED_AUTHORIZATION_SERVER_ENDPOINTS)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .with(authorizationServerConfigurer, Customizer.withDefaults());

        return http.build();
    }

    /**
     * Explicitly closes the rest of the Authorization Server's URI space. This chain
     * carries no SAS filters at all, so a request to an endpoint this platform does not
     * implement is denied by {@code AuthorizationFilter} without any SAS internal
     * processing running first.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain oauth2DisabledEndpointsFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/oauth2/**", "/.well-known/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(this::unauthorized)
                        .accessDeniedHandler(this::forbidden));

        return http.build();
    }

    @Bean
    @Order(4)
    public SecurityFilterChain apiFilterChain(HttpSecurity http, ConsumerRepository consumerRepository,
                                               PlatformTransactionManager transactionManager,
                                               UsageMeter usageMeter, JwtDecoder jwtDecoder) throws Exception {
        ApiKeyAuthFilter apiKeyAuthFilter =
                new ApiKeyAuthFilter(consumerRepository, new TransactionTemplate(transactionManager), usageMeter);
        OAuth2UsageMeteringFilter oauth2UsageMeteringFilter = new OAuth2UsageMeteringFilter(consumerRepository,
                usageMeter, new TransactionTemplate(transactionManager));

        http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(oauth2UsageMeteringFilter, ApiKeyAuthFilter.class)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(new OAuth2ConsumerAuthenticationConverter(consumerRepository))))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(this::unauthorized)
                        .accessDeniedHandler(this::forbidden));

        return http.build();
    }

    @Bean
    @Order(5)
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
