package com.springtime.bigquery.security;

import com.springtime.bigquery.config.AppSecurityProperties;
import com.springtime.bigquery.web.ErrorCode;
import com.springtime.bigquery.web.ProblemResponseWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Stateless API security.
 *
 * <p>Two authentication mechanisms can run together: a local shared secret in {@code X-API-Key}
 * (with a previous-key overlap window) and OIDC/JWT bearer tokens. There is no Basic auth, form
 * login, session, or CSRF token. If neither mechanism is configured, startup validation fails.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            AppSecurityProperties securityProperties,
            ProblemResponseWriter problemWriter,
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri:}") String issuerUri,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri:}") String jwkSetUri) throws Exception {

        boolean apiKeyEnabled = securityProperties.apiKeyEnabled();
        boolean oidcEnabled = StringUtils.hasText(issuerUri) || StringUtils.hasText(jwkSetUri);

        AuthenticationEntryPoint authenticationEntryPoint = (request, response, exception) -> {
            if (oidcEnabled) {
                response.addHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            }
            if (apiKeyEnabled) {
                response.addHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey realm=\"springtime\"");
            }
            problemWriter.write(request, response, ErrorCode.AUTHENTICATION_REQUIRED,
                    "Authentication required");
        };
        AccessDeniedHandler accessDeniedHandler = (request, response, exception) -> {
            if (oidcEnabled) {
                response.addHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
            }
            if (apiKeyEnabled) {
                response.addHeader(HttpHeaders.WWW_AUTHENTICATE, "ApiKey realm=\"springtime\"");
            }
            problemWriter.write(request, response, ErrorCode.ACCESS_DENIED,
                    "Insufficient scope or authority");
        };

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.GET,
                                "/actuator/health/liveness",
                                "/actuator/health/readiness",
                                // Static read-only demo console; it still needs an API key for data.
                                "/demo",
                                "/demo/**").permitAll()
                        .requestMatchers("/actuator/**")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.admin")
                        // Docs and the pinned contract are metadata; disable springdoc in shared environments.
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/openapi/**")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.POST, "/api/v1/query")
                        .hasAnyAuthority("ROLE_API", "SCOPE_bigquery.query")
                        .requestMatchers(HttpMethod.POST, "/api/v1/retail/events")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.write", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/retail/events/dead-letters")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.POST, "/api/v1/retail/events/dead-letters/*/replay")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.POST, "/api/v1/retail/inventory/projection")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.POST, "/api/v1/retail/replenishment-runs")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.write", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/retail/replenishment-runs/*/proposals/*/approve",
                                "/api/v1/retail/replenishment-runs/*/proposals/*/reject",
                                "/api/v1/retail/replenishment-runs/*/proposals/*/order")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.write", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.POST, "/api/v1/retail/purchase-orders")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.write", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/retail/purchase-orders/*/submit",
                                "/api/v1/retail/purchase-orders/*/cancel")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.write", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.POST, "/api/v1/retail/purchase-orders/*/transmit")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.write", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/retail/supplier-transmissions/**")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.read", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/retail/supplier-transmissions/*/retry",
                                "/api/v1/retail/supplier-transmissions/*/ack")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/retail/purchase-orders/**")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.read", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/retail/replenishment-runs/**")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.read", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/retail/inventory/reconciliation")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/retail/inventory/movements")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.read", "SCOPE_retail.admin")
                        .requestMatchers(HttpMethod.GET, "/api/v1/retail/**")
                        .hasAnyAuthority("ROLE_API", "SCOPE_retail.read", "SCOPE_retail.admin")
                        .requestMatchers("/api/v1/jobs/**")
                        .hasAnyAuthority("ROLE_API", "SCOPE_bigquery.query")
                        .requestMatchers("/api/v1/datasets/**")
                        .hasAnyAuthority("ROLE_API", "SCOPE_bigquery.read", "SCOPE_bigquery.query")
                        // Known API routes are matched above; unknown API routes reach MVC so the
                        // client gets a conventional 404 instead of a misleading 403.
                        .requestMatchers("/api/**").authenticated()
                        // Non-API routes must opt in explicitly.
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));

        if (apiKeyEnabled) {
            http.addFilterAfter(
                    new ApiKeyAuthenticationFilter(
                            securityProperties.apiKeys(), securityProperties.adminApiKeys(), problemWriter),
                    SecurityContextHolderFilter.class);
        }
        http.addFilterBefore(new AuthenticationContextFilter(), AuthorizationFilter.class);

        if (oidcEnabled) {
            http.oauth2ResourceServer(oauth2 -> oauth2
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler)
                    .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter(securityProperties))));
        }

        return http.build();
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter(AppSecurityProperties properties) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("sub");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> authorities(jwt, properties));
        return converter;
    }

    static List<GrantedAuthority> authorities(Jwt jwt, AppSecurityProperties properties) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>();
        String rolesClaim = StringUtils.hasText(properties.oidcRolesClaim())
                ? properties.oidcRolesClaim()
                : "roles";
        List<String> roles = jwt.getClaimAsStringList(rolesClaim);
        if (roles != null) {
            roles.forEach(role -> authorities.add(new SimpleGrantedAuthority("SCOPE_" + role)));
        }
        String hostedDomain = jwt.getClaimAsString("hd");
        if (hostedDomain != null && isGoogleIssuer(jwt)
                && properties.oidcAllowedDomains().contains(hostedDomain)) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_retail.read"));
        }
        String subject = jwt.getSubject();
        if (subject != null && properties.oidcAllowedSubjects().contains(subject)) {
            authorities.add(new SimpleGrantedAuthority("SCOPE_retail.read"));
        }
        return List.copyOf(authorities);
    }

    /** {@code hd} is a Google-specific claim; ignore it for any other identity provider. */
    private static boolean isGoogleIssuer(Jwt jwt) {
        String issuer = jwt.getClaimAsString("iss");
        if (issuer == null) {
            return false;
        }
        return issuer.equals("https://accounts.google.com")
                || issuer.startsWith("https://accounts.google.com/")
                || issuer.startsWith("https://securetoken.google.com/");
    }
}
