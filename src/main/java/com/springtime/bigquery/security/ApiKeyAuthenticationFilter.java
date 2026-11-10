package com.springtime.bigquery.security;

import com.springtime.bigquery.web.ErrorCode;
import com.springtime.bigquery.web.ProblemResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Authenticates one or more local shared secrets. It only handles requests that present
 * {@code X-API-Key}; requests without the header continue to the OIDC bearer-token filter. An
 * invalid key is rejected only when no bearer token is present, so OIDC can still authenticate
 * during rotation.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-API-Key";
    public static final String AUTHORITY = "ROLE_API";

    private final List<byte[]> readKeys;
    private final List<byte[]> adminKeys;
    private final ProblemResponseWriter problemWriter;

    public ApiKeyAuthenticationFilter(List<String> apiKeys, List<String> adminApiKeys,
                                      ProblemResponseWriter problemWriter) {
        this.readKeys = bytes(apiKeys);
        this.adminKeys = bytes(adminApiKeys);
        this.problemWriter = problemWriter;
    }

    private static List<byte[]> bytes(List<String> keys) {
        return keys.stream().map(key -> key.getBytes(StandardCharsets.UTF_8)).toList();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String provided = request.getHeader(HEADER);
        if (provided == null) {
            chain.doFilter(request, response);
            return;
        }
        boolean admin = matches(adminKeys, provided);
        boolean valid = admin || matches(readKeys, provided);
        if (!valid && request.getHeader(HttpHeaders.AUTHORIZATION) == null) {
            SecurityContextHolder.clearContext();
            problemWriter.write(request, response, ErrorCode.AUTHENTICATION_REQUIRED,
                    "Invalid " + HEADER + " header");
            return;
        }

        if (valid) {
            List<org.springframework.security.core.GrantedAuthority> authorities = new java.util.ArrayList<>();
            if (admin || adminKeys.isEmpty()) {
                // No admin key configured keeps the local/operator compatibility path.
                authorities.add(new SimpleGrantedAuthority(AUTHORITY));
            } else {
                authorities.add(new SimpleGrantedAuthority("SCOPE_retail.read"));
                authorities.add(new SimpleGrantedAuthority("SCOPE_bigquery.read"));
            }
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    admin ? "admin-key" : "api-key", null, authorities);
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
        }
        chain.doFilter(request, response);
    }

    private static boolean matches(List<byte[]> expectedKeys, String provided) {
        byte[] candidate = provided.getBytes(StandardCharsets.UTF_8);
        boolean matched = false;
        for (byte[] expected : expectedKeys) {
            matched |= MessageDigest.isEqual(expected, candidate);
        }
        return matched;
    }
}
