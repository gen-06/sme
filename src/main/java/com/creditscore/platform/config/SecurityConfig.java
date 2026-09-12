package com.creditscore.platform.config;

import com.creditscore.platform.billing.UsageMeter;
import com.creditscore.platform.identity.auth.AdminTokenFilter;
import com.creditscore.platform.identity.auth.ApiKeyAuthFilter;
import com.creditscore.platform.identity.auth.oauth2.OAuth2ConsumerAuthenticationConverter;
import com.creditscore.platform.identity.auth.oauth2.OAuth2SigningKeyService;
import com.creditscore.platform.identity.auth.oauth2.OAuth2UsageMeteringFilter;
import com.creditscore.platform.identity.auth.oauth2.RotatingClientSecretPasswordEncoder;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.ClientSecretAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;

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

    /**
     * A pure passthrough for a single hash (so {@code ConsumerProvisioningService}'s
     * {@code encode}/normal secret checks are unaffected), but able to check a raw
     * secret against a {@code primary|previous} composite string during a client-secret
     * rotation's grace period — see {@link RotatingClientSecretPasswordEncoder} and
     * {@code Consumer.getEffectiveOauthClientSecret}. Wired into
     * {@code ClientSecretAuthenticationProvider} in {@link #authorizationServerFilterChain}
     * so the token endpoint's own client authentication uses this behavior too, not just
     * this app's own code.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new RotatingClientSecretPasswordEncoder(PasswordEncoderFactories.createDelegatingPasswordEncoder());
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(OAuth2SigningKeyService signingKeyService) {
        JWKSet jwkSet = new JWKSet(signingKeyService.getOrCreateSigningKey());
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
    public OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate,
                                                             RegisteredClientRepository registeredClientRepository) {
        JdbcOAuth2AuthorizationService service =
                new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModules(
                SecurityJackson2Modules.getModules(JdbcOAuth2AuthorizationService.class.getClassLoader()));
        objectMapper.registerModule(new OAuth2AuthorizationServerJackson2Module());

        JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper rowMapper =
                new JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper(registeredClientRepository);
        rowMapper.setObjectMapper(objectMapper);
        service.setAuthorizationRowMapper(rowMapper);

        JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper parametersMapper =
                new JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper();
        parametersMapper.setObjectMapper(objectMapper);
        service.setAuthorizationParametersMapper(parametersMapper);

        return service;
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
    public SecurityFilterChain authorizationServerFilterChain(HttpSecurity http, PasswordEncoder passwordEncoder)
            throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();

        http
                .securityMatcher(SUPPORTED_AUTHORIZATION_SERVER_ENDPOINTS)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .with(authorizationServerConfigurer, configurer -> configurer
                        .clientAuthentication(clientAuth -> clientAuth.authenticationProviders(providers -> providers
                                .stream()
                                .filter(ClientSecretAuthenticationProvider.class::isInstance)
                                .map(ClientSecretAuthenticationProvider.class::cast)
                                .forEach(provider -> provider.setPasswordEncoder(passwordEncoder)))));

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
                // "/actuator" (bare) is Spring Boot Actuator's own auto-registered
                // discovery/index endpoint — it always exists regardless of
                // management.endpoints.web.exposure.include, and only links to whatever
                // IS exposed (just /actuator/health here). Listed explicitly here so it's
                // PERMITTED (public, no auth) rather than left to fall through to
                // catchAllFilterChain's denyAll() below, which would 404 it. Before this
                // was added, an unmatched "/actuator" instead bypassed Spring Security's
                // filter chain entirely and reached the servlet layer unauthenticated,
                // regardless of any authorizeHttpRequests rule — confirmed live (it
                // served 200 with real content while unmatched) — which is the gap
                // catchAllFilterChain now closes for every other unmatched path.
                //
                // "/v3/api-docs.yaml" is springdoc's YAML sibling of "/v3/api-docs" — a
                // literal path, not matched by "/v3/api-docs/**" (no slash after
                // "api-docs"). Listed explicitly for the same reason as "/actuator":
                // catchAllFilterChain would otherwise 404 it. Confirmed live: worked
                // (200) before catchAllFilterChain existed, 404'd once it did, until
                // added here.
                .securityMatcher("/swagger-ui/**", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui.html",
                        "/actuator", "/actuator/health")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Final fallback chain: {@code @Order(6)} means Spring Security only ever reaches
     * this for a path that matched none of chains 1-5 above, so it changes nothing for
     * any path already handled today. It exists because this app has no other
     * default-deny — without it, a path matching no {@code securityMatcher} bypasses
     * Spring Security's filter chain entirely and reaches the servlet layer completely
     * unauthenticated, regardless of any {@code authorizeHttpRequests} rule elsewhere
     * (confirmed live: {@code /actuator}'s auto-registered index endpoint served 200
     * with real content before it was added to {@link #publicFilterChain}'s matcher).
     * A future dependency that auto-registers a new mapped endpoint (the same way
     * Actuator did) is denied here by default instead of silently becoming reachable.
     *
     * <p>Returns a bare 404 rather than 401/403 for a directly-requested unmatched path:
     * the status a genuinely nonexistent path already got is preserved (this chain
     * doesn't change that), and an endpoint that exists but was never meant to be
     * reachable here should look identical to "doesn't exist" rather than confirm it's
     * real. Both the entry point AND the access-denied handler are wired to the same
     * 404: {@code denyAll()} against every caller here is unauthenticated (anonymous),
     * and {@code ExceptionTranslationFilter} routes an anonymous caller's
     * {@code AccessDeniedException} to the authentication entry point, not the
     * access-denied handler — only an authenticated-but-unauthorized caller reaches the
     * latter. Leaving the entry point unset falls back to Spring Security's default
     * {@code Http403ForbiddenEntryPoint} (confirmed live: produced a 403 here before
     * this was added).
     *
     * <p>{@code dispatcherTypeMatchers(ERROR, ASYNC).permitAll()} is required, not
     * cosmetic: Boot's {@code SecurityFilterAutoConfiguration} registers this app's
     * whole filter chain for {@code ERROR} and {@code ASYNC} dispatches too, and
     * {@code authorizeHttpRequests} rules apply to every dispatcher type by default. An
     * unhandled exception anywhere in the app forwards internally to {@code /error},
     * which matches no {@code securityMatcher} above and would otherwise re-enter this
     * chain's {@code denyAll()} — silently turning every 4xx/5xx in the entire
     * application into an empty 404 from {@link #notFound}, masking the real status and
     * body {@code BasicErrorController} would have written (confirmed live: an
     * authenticated request to a nonexistent {@code /api/**} sub-path returned an empty
     * 404 instead of {@code BasicErrorController}'s JSON, before this permit was added).
     * This permit does not reopen {@code /error} to a direct external hit: a directly
     * requested {@code GET /error} is a {@code REQUEST} dispatch, still denied below.
     * {@code ERROR} is the dispatch this app actually hits today and is what the live
     * check above covers; {@code ASYNC} is permitted for the identical reason
     * ({@code authorizeHttpRequests} applies to it too) but is currently unexercised — no
     * controller here returns {@code Callable}/{@code DeferredResult}/a reactive type.
     * Kept anyway so the first such endpoint doesn't inherit this exact bug.
     */
    @Bean
    @Order(6)
    public SecurityFilterChain catchAllFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .dispatcherTypeMatchers(jakarta.servlet.DispatcherType.ERROR,
                                jakarta.servlet.DispatcherType.ASYNC).permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(this::notFoundEntryPoint)
                        .accessDeniedHandler(this::notFoundAccessDenied));

        return http.build();
    }

    private void notFoundEntryPoint(jakarta.servlet.http.HttpServletRequest request,
                                     jakarta.servlet.http.HttpServletResponse response,
                                     org.springframework.security.core.AuthenticationException exception)
            throws IOException {
        response.setStatus(404);
    }

    private void notFoundAccessDenied(jakarta.servlet.http.HttpServletRequest request,
                                       jakarta.servlet.http.HttpServletResponse response,
                                       org.springframework.security.access.AccessDeniedException exception)
            throws IOException {
        response.setStatus(404);
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
