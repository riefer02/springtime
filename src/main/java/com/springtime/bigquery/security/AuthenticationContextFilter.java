package com.springtime.bigquery.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Copies the authenticated principal into request attributes/MDC so access logs and application
 * logs identify the caller without logging tokens. Runs inside the security chain, before
 * authorization.
 */
public class AuthenticationContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)) {
            String clientId = authentication.getName() == null ? "-" : authentication.getName();
            clientId = clientId.replaceAll("[\\r\\n]", "");
            if (clientId.length() > 128) {
                clientId = clientId.substring(0, 128);
            }
            request.setAttribute("clientId", clientId);
            MDC.put("clientId", clientId);
        }
        chain.doFilter(request, response);
    }
}
