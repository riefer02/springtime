package com.springtime.bigquery.retail.purchase.api;

import com.springtime.bigquery.ServletSliceTestConfiguration;
import com.springtime.bigquery.error.InvalidRequestException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderEventView;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderLineView;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderView;
import com.springtime.bigquery.retail.purchase.application.PurchaseOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import com.springtime.bigquery.error.BusinessRuleException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PurchaseOrderController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ServletSliceTestConfiguration.class)
class PurchaseOrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PurchaseOrderService orderService;

    @Test
    void mapsControlPlaneDatabaseFailureTo503() throws Exception {
        when(orderService.list(any(), any(), any(), any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("postgres down"));

        mockMvc.perform(get("/api/v1/retail/purchase-orders"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void mapsABusinessRuleViolationToA409() throws Exception {
        when(orderService.create(any(), any()))
                .thenThrow(new BusinessRuleException("No approved proposals for the requested run/store/supplier"));

        mockMvc.perform(post("/api/v1/retail/purchase-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runId": "run-1", "storeId": "ST-001", "supplierId": "SUP-001"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void mapsADataIntegrityViolationToAConflictNotAnOutage() throws Exception {
        when(orderService.create(any(), any()))
                .thenThrow(new DataIntegrityViolationException("duplicate scope"));

        mockMvc.perform(post("/api/v1/retail/purchase-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runId": "run-1", "storeId": "ST-001", "supplierId": "SUP-001"}"""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    void createsADraft() throws Exception {
        when(orderService.create(any(), anyString())).thenReturn(orderView("DRAFT", 0));

        mockMvc.perform(post("/api/v1/retail/purchase-orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runId":"run-1","storeId":"ST-001","supplierId":"SUP-001"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.lineCount").value(2));
    }

    @Test
    void listsOrders() throws Exception {
        when(orderService.list(null, null, null, null, 50))
                .thenReturn(new PageResponse<>(List.of(orderView("DRAFT", 0)), null, 50));

        mockMvc.perform(get("/api/v1/retail/purchase-orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].poId").value("po-1"))
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(jsonPath("$.nextPageToken").isEmpty());
    }

    @Test
    void rejectsAMalformedPurchaseOrderPageToken() throws Exception {
        when(orderService.list(null, null, null, "not-a-token", 50))
                .thenThrow(new InvalidRequestException(
                        "pageToken is malformed; use the nextPageToken from a previous response"));

        mockMvc.perform(get("/api/v1/retail/purchase-orders")
                        .param("pageToken", "not-a-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void getsOneOrderAndLines() throws Exception {
        when(orderService.get("po-1")).thenReturn(orderView("DRAFT", 0));
        when(orderService.lines("po-1", null, 200)).thenReturn(
                new PageResponse<>(List.of(new PurchaseOrderLineView(
                        "po-1", 1, "SKU-1", "SKU-1", "Product SKU-1", "SUP-001",
                        10, "10.00", Instant.parse("2026-11-09T12:00:00Z"))), "MQ", 200));

        mockMvc.perform(get("/api/v1/retail/purchase-orders/po-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.poId").value("po-1"));
        mockMvc.perform(get("/api/v1/retail/purchase-orders/po-1/lines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderedUnits").value(10))
                .andExpect(jsonPath("$.nextPageToken").value("MQ"));
    }

    @Test
    void submitsADraft() throws Exception {
        when(orderService.submit(anyString(), anyInt(), any(), any(), anyString()))
                .thenReturn(orderView("SUBMITTED", 1));

        mockMvc.perform(post("/api/v1/retail/purchase-orders/po-1/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"supplierReference\":\"SUP-REF-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));
    }

    @Test
    void cancelsADraft() throws Exception {
        when(orderService.cancel(anyString(), anyInt(), any(), anyString()))
                .thenReturn(orderView("CANCELLED", 1));

        mockMvc.perform(post("/api/v1/retail/purchase-orders/po-1/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"note\":\"duplicate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void listsHistory() throws Exception {
        when(orderService.history("po-1", null, 50)).thenReturn(
                new PageResponse<>(List.of(new PurchaseOrderEventView(
                        "e-1", "po-1", null, "DRAFT", "planner", "created",
                        Instant.parse("2026-11-09T12:00:00Z"))), null, 50));

        mockMvc.perform(get("/api/v1/retail/purchase-orders/po-1/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].toStatus").value("DRAFT"))
                .andExpect(jsonPath("$.nextPageToken").isEmpty());
    }

    private static PurchaseOrderView orderView(String status, int version) {
        return new PurchaseOrderView(
                "po-1", "PO-1", "run-1:ST-001:SUP-001", "run-1", "ST-001",
                "SUP-001", "Supplier 1", status, "USD", "30.00", 2,
                "planner", null, LocalDate.of(2026, 11, 20), null, version,
                Instant.parse("2026-11-09T12:00:00Z"),
                status.equals("SUBMITTED") ? Instant.parse("2026-11-09T12:01:00Z") : null,
                status.equals("CANCELLED") ? Instant.parse("2026-11-09T12:01:00Z") : null);
    }
}
