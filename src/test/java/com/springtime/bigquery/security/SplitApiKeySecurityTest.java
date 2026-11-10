package com.springtime.bigquery.security;

import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.QueryResponse;
import com.springtime.bigquery.retail.purchase.application.PurchaseOrderService;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderView;
import com.springtime.bigquery.service.BigQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the read-only/admin API-key split at the HTTP layer. A matcher-order regression that
 * silently granted the read key write access would pass the filter unit tests but fail here.
 */
@SpringBootTest(properties = {
        "app.security.api-key=read-key-0123456789abcdef0123456789",
        "app.security.admin-api-key=admin-key-0123456789abcdef0123456789",
        "bigquery.project-id=test-project"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SplitApiKeySecurityTest {

    private static final String READ_KEY = "read-key-0123456789abcdef0123456789";
    private static final String ADMIN_KEY = "admin-key-0123456789abcdef0123456789";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BigQueryService bigQueryService;

    @MockitoBean
    private PurchaseOrderService purchaseOrderService;

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/retail/events",
            "/api/v1/retail/purchase-orders",
            "/api/v1/retail/replenishment-runs",
            "/api/v1/retail/inventory/projection",
            "/api/v1/retail/events/dead-letters/evt-1/replay",
            "/api/v1/retail/purchase-orders/00000000-0000-0000-0000-000000000001/submit",
            "/api/v1/retail/supplier-transmissions/00000000-0000-0000-0000-000000000002/retry"
    })
    void readOnlyKeyIsForbiddenOnWriteEndpoints(String path) throws Exception {
        mockMvc.perform(post(path)
                        .header(ApiKeyAuthenticationFilter.HEADER, READ_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void readOnlyKeyCannotRunOperatorQueries() throws Exception {
        mockMvc.perform(post("/api/v1/query")
                        .header(ApiKeyAuthenticationFilter.HEADER, READ_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sql": "SELECT 1"}"""))
                .andExpect(status().isForbidden());
    }

    @Test
    void readOnlyKeyCanReadPaginatedLists() throws Exception {
        when(purchaseOrderService.list(isNull(), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(order()), "next-token", 50));

        mockMvc.perform(get("/api/v1/retail/purchase-orders")
                        .header(ApiKeyAuthenticationFilter.HEADER, READ_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].poId").value("po-1"))
                .andExpect(jsonPath("$.nextPageToken").value("next-token"));
    }

    @Test
    void adminKeyCanCreateAndQuery() throws Exception {
        when(purchaseOrderService.create(any(), eq("admin-key"))).thenReturn(order());
        when(bigQueryService.execute(any())).thenReturn(new QueryResponse(
                "job-1", List.of(), List.of(), 0, 0L, 0L, 0L, false, true, null, false));

        mockMvc.perform(post("/api/v1/retail/purchase-orders")
                        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runId": "run-1", "storeId": "ST-001", "supplierId": "SUP-001"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poId").value("po-1"));

        mockMvc.perform(post("/api/v1/query")
                        .header(ApiKeyAuthenticationFilter.HEADER, ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sql": "SELECT 1"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"));
    }

    private static PurchaseOrderView order() {
        return new PurchaseOrderView(
                "po-1", "PO-1", "key-1", "run-1", "ST-001", "SUP-001", "Supplier 1",
                "DRAFT", "USD", "10.00", 1, "planner", null, LocalDate.of(2026, 11, 20),
                null, 0, Instant.parse("2026-11-10T00:00:00Z"), null, null);
    }
}
