package com.springtime.bigquery.security;

import com.springtime.bigquery.config.AppSecurityProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigAuthoritiesTest {

    @Test
    void mapsRolesDomainsAndSubjectsToAuthorities() {
        AppSecurityProperties properties = new AppSecurityProperties(
                "", "", false, "roles",
                List.of("example.com"), List.of("service-account-subject"), false, "");

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("iss", "https://accounts.google.com")
                .claim("sub", "service-account-subject")
                .claim("hd", "example.com")
                .claim("roles", List.of("bigquery.query"))
                .build();

        assertThat(SecurityConfig.authorities(jwt, properties))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("SCOPE_bigquery.query", "SCOPE_retail.read")
                .doesNotContain("ROLE_API");
    }

    @Test
    void domainAndSubjectGrantOnlyRetailReadNotOperatorRole() {
        AppSecurityProperties properties = new AppSecurityProperties(
                "", "", false, "roles", List.of("example.com"), List.of(), false, "");

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("iss", "https://accounts.google.com")
                .claim("sub", "employee")
                .claim("hd", "example.com")
                .build();

        assertThat(SecurityConfig.authorities(jwt, properties))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("SCOPE_retail.read");
    }

    @Test
    void ignoresHostedDomainFromANonGoogleIssuer() {
        AppSecurityProperties properties = new AppSecurityProperties(
                "", "", false, "roles", List.of("example.com"), List.of(), false, "");

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("iss", "https://keycloak.example.com/realms/retail")
                .claim("sub", "employee")
                .claim("hd", "example.com")
                .build();

        assertThat(SecurityConfig.authorities(jwt, properties)).isEmpty();
    }

    @Test
    void doesNotGrantRoleForUnlistedDomainOrSubject() {
        AppSecurityProperties properties = new AppSecurityProperties(
                "", "", false, "roles",
                List.of("example.com"), List.of("allowed-subject"), false, "");

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claim("sub", "someone-else")
                .claim("hd", "other.example")
                .build();

        assertThat(SecurityConfig.authorities(jwt, properties)).isEmpty();
    }
}
