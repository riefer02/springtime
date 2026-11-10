package com.springtime.bigquery.retail.inventory.api;

import com.springtime.bigquery.ServletSliceTestConfiguration;
import com.springtime.bigquery.error.InvalidRequestException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.retail.inventory.application.InventoryService;
import com.springtime.bigquery.retail.inventory.model.InventoryMovementView;
import com.springtime.bigquery.retail.inventory.model.ReconciliationReport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InventoryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ServletSliceTestConfiguration.class)
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InventoryService inventoryService;

    @Test
    void listsMovements() throws Exception {
        when(inventoryService.movements(eq("ST-001"), isNull(), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(new PageResponse<>(List.of(new InventoryMovementView(
                        "evt-1", "SALE", Instant.parse("2026-11-09T11:00:00Z"),
                        LocalDate.of(2026, 11, 9), "ST-001", "SKU-0001",
                        -2L, null, "USD", "retail-api", "evt-1", "1")), null, 50));

        mockMvc.perform(get("/api/v1/retail/inventory/movements").param("storeId", "ST-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$.items[0].quantityDelta").value(-2))
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(jsonPath("$.nextPageToken").isEmpty());
    }

    @Test
    void rejectsAMalformedMovementPageToken() throws Exception {
        when(inventoryService.movements(any(), any(), any(), any(), eq("not-a-token"), anyInt()))
                .thenThrow(new InvalidRequestException(
                        "pageToken is malformed; use the nextPageToken from a previous response"));

        mockMvc.perform(get("/api/v1/retail/inventory/movements")
                        .param("pageToken", "not-a-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void reconcilesInventory() throws Exception {
        when(inventoryService.reconciliation(any(), any(), any(), anyInt()))
                .thenReturn(new ReconciliationReport(
                        "BALANCED", LocalDate.of(2026, 11, 9), 0, 0, 50, false, 0,
                        Instant.parse("2026-11-09T12:00:00Z"), List.of()));

        mockMvc.perform(get("/api/v1/retail/inventory/reconciliation")
                        .param("asOfDate", "2026-11-09"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BALANCED"))
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void rejectsMalformedDate() throws Exception {
        mockMvc.perform(get("/api/v1/retail/inventory/movements").param("from", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }
}
