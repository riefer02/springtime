package com.springtime.bigquery.security;

import com.springtime.bigquery.web.ProblemResponseWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ApiKeyAuthenticationFilterTest {

    private final ApiKeyAuthenticationFilter filter = new ApiKeyAuthenticationFilter(
            List.of("read-key-0123456789abcdef0123456789"),
            List.of("admin-key-0123456789abcdef0123456789"),
            mock(ProblemResponseWriter.class));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readKeyGrantsOnlyReadAuthoritiesWhenSplitIsConfigured() throws Exception {
        authenticate("read-key-0123456789abcdef0123456789");

        assertThat(authorities()).containsExactlyInAnyOrder(
                "SCOPE_retail.read", "SCOPE_bigquery.read");
    }

    @Test
    void adminKeyGrantsTheOperatorRole() throws Exception {
        authenticate("admin-key-0123456789abcdef0123456789");

        assertThat(authorities()).containsExactly("ROLE_API");
    }

    private void authenticate(String key) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/retail/stores");
        request.addHeader(ApiKeyAuthenticationFilter.HEADER, key);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    private static List<String> authorities() {
        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }
}
