package com.referralhub.app;

import com.referralhub.common.error.ApiError;
import com.referralhub.common.json.Json;
import org.springframework.boot.actuate.autoconfigure.security.servlet.EndpointRequest;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Who may call what.
 *
 * <p>Three tiers, and the reasoning for each boundary:
 *
 * <ul>
 *   <li><b>Public</b> — search, the console, health, metrics and the API documentation. Job
 *       search is the product's front door; requiring an account to look at public job postings
 *       would be worse than pointless.</li>
 *   <li><b>Authenticated</b> — everything that acts for a person: creating and answering
 *       referral requests, employee verification, reading standing. The acting identity comes
 *       from the token, never from the request body.</li>
 *   <li><b>Administrator</b> — registering ATS boards and forcing crawls. These spend somebody
 *       else's infrastructure budget, so they are not something an ordinary account can do.</li>
 * </ul>
 *
 * <p>Stateless: no session, no CSRF token. CSRF protection exists to stop a browser attaching
 * ambient credentials to a cross-site request, and a bearer token in an Authorization header is
 * not ambient — the attacker's page cannot read it. Disabling CSRF here is correct rather than
 * merely convenient, which is not true of a cookie-authenticated API.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .headers(headers -> headers
                        .referrerPolicy(referrer -> referrer.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        .frameOptions(frame -> frame.deny()))
                .authorizeHttpRequests(auth -> auth
                        // --- public -------------------------------------------------------
                        .requestMatchers("/", "/index.html", "/favicon.ico", "/assets/**").permitAll()
                        // EndpointRequest rather than string paths. Actuator endpoints are
                        // served by their own handler mapping and can sit under a configurable
                        // base path, so matching them by literal URL is fragile — which is how
                        // /actuator/prometheus ended up behind authentication while
                        // /actuator/health/** stayed reachable.
                        .requestMatchers(EndpointRequest.to(HealthEndpoint.class)).permitAll()
                        .requestMatchers(EndpointRequest.to("prometheus")).permitAll()
                        // Everything else the actuator exposes is operational detail.
                        .requestMatchers(EndpointRequest.toAnyEndpoint()).hasRole("ADMIN")
                        .requestMatchers("/docs/**", "/swagger-ui/**", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/search/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/dedup/**").permitAll()

                        // --- administrator ------------------------------------------------
                        .requestMatchers(HttpMethod.POST, "/api/v1/ingestion/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/search/index/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/dedup/**").hasRole("ADMIN")
                        // Reading crawl state is operational, not sensitive, but it still says
                        // which companies we track, so it needs an account.
                        .requestMatchers(HttpMethod.GET, "/api/v1/ingestion/**").authenticated()

                        // --- everything else ----------------------------------------------
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(Customizer.withDefaults())
                        // Without these, a rejected token yields an empty body and a bare status,
                        // which is indistinguishable from a routing mistake on the caller's side.
                        .authenticationEntryPoint((request, response, exception) ->
                                write(response, HttpStatus.UNAUTHORIZED, "unauthenticated",
                                        "A valid bearer token is required"))
                        .accessDeniedHandler((request, response, exception) ->
                                write(response, HttpStatus.FORBIDDEN, "forbidden",
                                        "Your account may not perform this action")));
        return http.build();
    }

    private static void write(jakarta.servlet.http.HttpServletResponse response, HttpStatus status,
                              String code, String message) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(Json.write(ApiError.of(code, message)));
    }

    /**
     * Maps the token's {@code roles} claim onto Spring's authorities.
     *
     * <p>The default converter reads {@code scope}/{@code scp} and prefixes {@code SCOPE_}, which
     * would make every {@code hasRole} check above silently false — the most quietly dangerous
     * misconfiguration available here, because the application starts and every request 403s
     * rather than failing loudly at boot.
     */
    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
