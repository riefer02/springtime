package com.springtime.bigquery.retail.api;

import com.springtime.bigquery.ServletSliceTestConfiguration;
import com.springtime.bigquery.error.InvalidRequestException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.retail.model.InventoryPosition;
import com.springtime.bigquery.retail.model.RetailDashboard;
import com.springtime.bigquery.retail.model.RetailPage;
import com.springtime.bigquery.retail.model.StoreSummary;
import com.springtime.bigquery.retail.service.RetailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import com.springtime.bigquery.error.DatasetNotProvisionedException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RetailController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ServletSliceTestConfiguration.class)
class RetailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RetailService retailService;

    @Test
    void listsStores() throws Exception {
        when(retailService.stores(isNull(), anyInt())).thenReturn(new PageResponse<>(
                List.of(new StoreSummary("ST-001", "Store 01", "Northeast", "Boston", "MA",
                        "America/New_York", "DC-001", "URBAN")),
                null, 50));

        mockMvc.perform(get("/api/v1/retail/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].storeId").value("ST-001"))
                .andExpect(jsonPath("$.items[0].timeZone").value("America/New_York"))
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(jsonPath("$.nextPageToken").isEmpty());
    }

    @Test
    void returnsTheNextPageTokenForStores() throws Exception {
        when(retailService.stores(isNull(), anyInt())).thenReturn(new PageResponse<>(
                List.of(new StoreSummary("ST-001", "Store 01", "Northeast", "Boston", "MA",
                        "America/New_York", "DC-001", "URBAN")),
                "MQ", 1));

        mockMvc.perform(get("/api/v1/retail/stores").param("pageSize", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextPageToken").value("MQ"));
    }

    @Test
    void rejectsAMalformedPageToken() throws Exception {
        when(retailService.stores(eq("not-a-token"), anyInt()))
                .thenThrow(new InvalidRequestException(
                        "pageToken is malformed; use the nextPageToken from a previous response"));

        mockMvc.perform(get("/api/v1/retail/stores").param("pageToken", "not-a-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void mapsAMissingDatasetToANonRetryable503() throws Exception {
        when(retailService.stores(isNull(), anyInt()))
                .thenThrow(new DatasetNotProvisionedException(
                        "Retail dataset or table is not available; run `make retail-schema` first"));

        mockMvc.perform(get("/api/v1/retail/stores"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATASET_NOT_PROVISIONED"))
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void returnsInventoryPage() throws Exception {
        when(retailService.inventory(eq("ST-001"), isNull(), isNull(), isNull(), anyInt()))
                .thenReturn(new RetailPage<>(List.of(new InventoryPosition(
                        "ST-001", "Store 01", "Northeast", "SKU-0001", "SKU-0001",
                        "Coffee 1", "Beverages", LocalDate.of(2026, 11, 9),
                        42, 12, 2, 52, 20, 60, 4.2, 12.3, 5,
                        "WATCH", LocalDate.of(2026, 11, 21), "4.25", "178.50")),
                        LocalDate.of(2026, 11, 9), 50, false));

        mockMvc.perform(get("/api/v1/retail/inventory").param("storeId", "ST-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-11-09"))
                .andExpect(jsonPath("$.items[0].sku").value("SKU-0001"))
                .andExpect(jsonPath("$.items[0].riskLevel").value("WATCH"))
                .andExpect(jsonPath("$.items[0].inventoryValue").value("178.50"));
    }

    @Test
    void returnsDashboard() throws Exception {
        when(retailService.dashboard(eq("ST-001"), isNull()))
                .thenReturn(new RetailDashboard("ST-001", LocalDate.of(2026, 11, 9),
                        1200, "18423.50", 3, 12, 430, "9821.25", 4, 2, 1));

        mockMvc.perform(get("/api/v1/retail/dashboard").param("storeId", "ST-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inventoryValue").value("18423.50"))
                .andExpect(jsonPath("$.openLateShipments").value(4));
    }
}
