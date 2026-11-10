package com.springtime.bigquery.security;

import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.QueryResponse;
import com.springtime.bigquery.retail.service.RetailService;
import com.springtime.bigquery.service.BigQueryService;
import com.springtime.bigquery.web.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.security.api-key=secret-0123456789abcdef0123456789",
        "app.security.api-key-previous=previous-0123456789abcdef0123456789",
        "bigquery.project-id=test-project"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BigQueryService bigQueryService;

    @MockitoBean
    private RetailService retailService;

    @Test
    void rejectsMissingApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sql": "SELECT 1"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists(RequestIdFilter.HEADER))
                .andExpect(header().string("X-API-Version", "1"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void rejectsWrongApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/query")
                        .header(ApiKeyAuthenticationFilter.HEADER, "wrong")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sql": "SELECT 1"}"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Invalid X-API-Key header"));
    }

    @Test
    void acceptsValidApiKey() throws Exception {
        when(bigQueryService.execute(any())).thenReturn(new QueryResponse(
                "job-1", List.of(), List.of(), 0, 0L, 0L, 0L, false, true, null, false));

        mockMvc.perform(post("/api/v1/query")
                        .header(ApiKeyAuthenticationFilter.HEADER, "secret-0123456789abcdef0123456789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sql": "SELECT 1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"));
    }

    @Test
    void acceptsThePreviousKeyDuringRotation() throws Exception {
        when(bigQueryService.execute(any())).thenReturn(new QueryResponse(
                "job-2", List.of(), List.of(), 0, 0L, 0L, 0L, false, true, null, false));

        mockMvc.perform(post("/api/v1/query")
                        .header(ApiKeyAuthenticationFilter.HEADER,
                                "previous-0123456789abcdef0123456789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sql": "SELECT 1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-2"));
    }

    @Test
    void retailEndpointsRequireAuthenticationButAcceptTheApiKey() throws Exception {
        when(retailService.stores(any(), anyInt())).thenReturn(new PageResponse<>(List.of(), null, 50));

        mockMvc.perform(get("/api/v1/retail/stores"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/retail/stores")
                        .header(ApiKeyAuthenticationFilter.HEADER, "secret-0123456789abcdef0123456789"))
                .andExpect(status().isOk());
    }

    @Test
    void retailEventRequiresAuthenticationAndAcceptsTheApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/retail/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt-sec-1","eventType":"SALE",
                                 "occurredAt":"2026-11-09T12:00:00Z","storeId":"ST-001"}"""))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/retail/events")
                        .header(ApiKeyAuthenticationFilter.HEADER, "secret-0123456789abcdef0123456789")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventId":"evt-sec-1","eventType":"SALE",
                                 "occurredAt":"%s","storeId":"ST-001",
                                 "productId":"SKU-0001","quantity":2}""".formatted(java.time.Instant.now().minusSeconds(60))))
                .andExpect(status().isAccepted());
    }

    @Test
    void unknownApiRoutesReturnNotFoundForAnAuthenticatedCaller() throws Exception {
        mockMvc.perform(get("/api/v1/not-a-real-endpoint")
                        .header(ApiKeyAuthenticationFilter.HEADER, "secret-0123456789abcdef0123456789"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ENDPOINT_NOT_FOUND"));
    }

    @Test
    void demoConsoleIsPublicButTheApiIsNot() throws Exception {
        mockMvc.perform(get("/demo/index.html"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/demo/app.js"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/retail/stores"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pinnedContractRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/openapi/springtime-v1.json"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/openapi/springtime-v1.json")
                        .header(ApiKeyAuthenticationFilter.HEADER, "secret-0123456789abcdef0123456789"))
                .andExpect(status().isOk());
    }

    @Test
    void livenessIsPublicButMetricsAreProtected() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/prometheus")
                        .header(ApiKeyAuthenticationFilter.HEADER, "secret-0123456789abcdef0123456789"))
                .andExpect(status().isOk());
    }
}
