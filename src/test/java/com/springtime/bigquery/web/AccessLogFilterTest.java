package com.springtime.bigquery.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.springtime.bigquery.config.AppHttpProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

class AccessLogFilterTest {

    private final AccessLogFilter filter = new AccessLogFilter(
            new AppHttpProperties(262_144, 100_000, 65_536, 100, 5_000_000, true));
    private final Logger logger = (Logger) LoggerFactory.getLogger(AccessLogFilter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attach() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void logsTheConcreteUriForStaticResources() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/openapi/springtime-v1.json");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/**");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(messages()).singleElement().satisfies(line -> assertThat(line)
                .contains("path=/openapi/springtime-v1.json")
                .contains("route=/**")
                .contains("status=200"));
    }

    @Test
    void logsTheRoutePatternForControllers() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/api/v1/retail/purchase-orders/po-1");
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,
                "/api/v1/retail/purchase-orders/{poId}");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(messages()).singleElement().satisfies(line -> assertThat(line)
                .contains("path=/api/v1/retail/purchase-orders/po-1")
                .contains("route=/api/v1/retail/purchase-orders/{poId}"));
    }

    private java.util.List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
