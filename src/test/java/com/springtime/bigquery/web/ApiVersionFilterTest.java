package com.springtime.bigquery.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class ApiVersionFilterTest {

    private final ApiVersionFilter filter = new ApiVersionFilter();

    @Test
    void addsVersionToApiResponses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/datasets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-API-Version")).isEqualTo("1");
    }

    @Test
    void doesNotAddVersionToActuatorResponses() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-API-Version")).isNull();
    }

    @Test
    void doesNotAdvertiseAnUnimplementedVersion() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v2/datasets");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader("X-API-Version")).isNull();
    }
}
