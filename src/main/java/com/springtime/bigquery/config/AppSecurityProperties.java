package com.springtime.bigquery.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.stream.Stream;

/**
 * Local API-key authentication and OIDC authority mapping.
 *
 * <p>{@code API_KEY_PREVIOUS} keeps a rotation overlap window. Google-issued ID tokens have no
 * OAuth scopes, so OIDC authorities come from a roles claim, an allowed Workspace domain
 * ({@code hd}), or an allowed service-account subject ({@code sub}). The API key is a local
 * operator credential; the {@code prod}/{@code production} profile rejects it unless
 * {@code allowApiKeyInProduction} is set.</p>
 */
@ConfigurationProperties(prefix = "app.security")
public record AppSecurityProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("") String apiKeyPrevious,
        @DefaultValue("false") boolean allowInsecureOidc,
        @DefaultValue("roles") String oidcRolesClaim,
        List<String> oidcAllowedDomains,
        List<String> oidcAllowedSubjects,
        @DefaultValue("false") boolean allowApiKeyInProduction,
        @DefaultValue("") String adminApiKey
) {

    public AppSecurityProperties {
        oidcAllowedDomains = oidcAllowedDomains == null ? List.of() : List.copyOf(oidcAllowedDomains);
        oidcAllowedSubjects = oidcAllowedSubjects == null ? List.of() : List.copyOf(oidcAllowedSubjects);
    }

    @Override
    public String toString() {
        return "AppSecurityProperties[apiKey=***, apiKeyPrevious=***, allowInsecureOidc="
                + allowInsecureOidc + ", oidcRolesClaim=" + oidcRolesClaim
                + ", oidcAllowedDomains=" + oidcAllowedDomains
                + ", oidcAllowedSubjects=" + oidcAllowedSubjects
                + ", allowApiKeyInProduction=" + allowApiKeyInProduction
                + ", adminApiKey=***]";
    }

    public boolean apiKeyEnabled() {
        return !apiKeys().isEmpty() || !adminApiKeys().isEmpty();
    }

    public List<String> apiKeys() {
        return Stream.of(apiKey, apiKeyPrevious)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    public List<String> adminApiKeys() {
        return Stream.of(adminApiKey)
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }
}
