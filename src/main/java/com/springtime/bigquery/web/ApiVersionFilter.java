package com.springtime.bigquery.web;

import com.springtime.bigquery.contract.ApiVersion;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Adds the informational API version header to every API response, including errors. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 2)
public class ApiVersionFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (path.equals("/api/v1") || path.startsWith("/api/v1/")) {
            response.setHeader(ApiVersion.HEADER, ApiVersion.CURRENT);
        }
        chain.doFilter(request, response);
    }
}
