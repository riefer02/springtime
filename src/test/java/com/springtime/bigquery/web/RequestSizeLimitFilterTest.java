package com.springtime.bigquery.web;

import com.springtime.bigquery.config.AppHttpProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RequestSizeLimitFilterTest {

    private final ApiProblemFactory problemFactory = new ApiProblemFactory();
    private final ProblemResponseWriter writer = new ProblemResponseWriter(
            JsonMapper.builder().build(), problemFactory);
    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(
            new AppHttpProperties(10, 1_000, 1_000, 100, 5_000_000, true), writer);

    @Test
    void rejectsBodiesAboveTheConfiguredLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/query");
        request.setContent(new byte[11]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentType()).startsWith("application/problem+json");
        assertThat(response.getContentAsString()).contains("Payload too large");
    }

    @Test
    void rejectsChunkedBodiesAboveTheLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/query") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[11]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response,
                (req, res) -> req.getInputStream().readAllBytes());

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("Payload too large");
    }

    @Test
    void repeatedInputStreamAccessDoesNotResetTheBudget() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/query") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[10]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        int[] reads = new int[2];

        filter.doFilter(request, response, (req, res) -> {
            byte[] buffer = new byte[6];
            reads[0] = req.getInputStream().read(buffer);
            reads[1] = req.getInputStream().read(buffer);
        });

        assertThat(reads).containsExactly(6, 4);
    }

    @Test
    void allowsChunkedBodiesAtTheLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/query") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContent(new byte[10]);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response,
                (req, res) -> req.getInputStream().readAllBytes());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void allowsBodiesAtOrBelowTheLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/query");
        request.setContent(new byte[10]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chain.getRequest()).isNotNull();
    }
}
