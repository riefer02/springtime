package com.springtime.bigquery.web;

import com.springtime.bigquery.config.AppHttpProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * One structured access line per request, including status, latency, correlation id and the
 * authenticated client. Runs outside the security chain so 401/403/413 responses are captured too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AccessLogFilter.class);

    private final AppHttpProperties properties;

    public AccessLogFilter(AppHttpProperties properties) {
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.accessLogEnabled();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMillis = (System.nanoTime() - started) / 1_000_000;
            Object client = request.getAttribute("clientId");
            Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            // Log the concrete URI: static resources and the pinned contract resolve to the
            // catch-all "/**" handler pattern, which would erase which resource was fetched.
            String path = request.getRequestURI();
            String route = pattern == null ? path : pattern.toString();
            log.info("http method={} path={} route={} status={} durationMs={} client={} remote={} requestId={}",
                    request.getMethod(),
                    path,
                    route,
                    response.getStatus(),
                    durationMillis,
                    client == null ? "-" : client,
                    request.getRemoteAddr(),
                    MDC.get(RequestIdFilter.MDC_KEY));
        }
    }
}
