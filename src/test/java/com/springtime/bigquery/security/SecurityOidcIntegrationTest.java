package com.springtime.bigquery.security;

import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.QueryResponse;
import com.springtime.bigquery.retail.event.application.RetailEventPublisher;
import com.springtime.bigquery.retail.event.dto.AcceptedEvent;
import com.springtime.bigquery.retail.service.RetailService;
import com.springtime.bigquery.service.BigQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.security.api-key=",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://example.invalid/jwks",
        "spring.security.oauth2.resourceserver.jwt.audiences=springtime-api",
        "bigquery.project-id=test-project"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityOidcIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private BigQueryService bigQueryService;

    @MockitoBean
    private RetailService retailService;

    @MockitoBean
    private RetailEventPublisher retailEventPublisher;

    @Test
    void readScopeCanReadRetailButCannotWrite() throws Exception {
        when(retailService.stores(any(), anyInt())).thenReturn(new PageResponse<>(List.of(), null, 50));

        mockMvc.perform(get("/api/v1/retail/stores")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_retail.read"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/retail/events")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_retail.read")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void writeScopeCanPublishEvents() throws Exception {
        when(retailEventPublisher.publish(any()))
                .thenReturn(AcceptedEvent.accepted("evt-1", "msg-1", "retail-events"));

        mockMvc.perform(post("/api/v1/retail/events")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_retail.write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson()))
                .andExpect(status().isAccepted());
    }

    @Test
    void operatorScopeCanQuery() throws Exception {
        when(bigQueryService.execute(any())).thenReturn(new QueryResponse(
                "job-1", List.of(), List.of(), 0, 0L, 0L, 0L, false, false, null, false));

        mockMvc.perform(post("/api/v1/query")
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_bigquery.query")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sql":"SELECT 1"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void missingBearerIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/v1/retail/stores"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
    }

    private static String eventJson() {
        return """
                {"eventId":"evt-1","eventType":"SALE",
                 "occurredAt":"2026-11-09T12:00:00Z","storeId":"ST-001",
                 "productId":"SKU-0001","quantity":2}""";
    }
}
