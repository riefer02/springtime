package com.springtime.bigquery.retail.replenishment.api;

import com.springtime.bigquery.ServletSliceTestConfiguration;
import com.springtime.bigquery.error.InvalidRequestException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentProposalView;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentRunView;
import com.springtime.bigquery.retail.replenishment.application.ReplenishmentRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReplenishmentRunController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ServletSliceTestConfiguration.class)
class ReplenishmentRunControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReplenishmentRunService runService;

    @Test
    void createsARun() throws Exception {
        when(runService.create(any(), anyString())).thenReturn(runView());

        mockMvc.perform(post("/api/v1/retail/replenishment-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"runId":"run-1","storeId":"ST-001",
                                 "asOfDate":"2026-11-09","targetCoverDays":14}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.proposalCount").value(2));
    }

    @Test
    void rejectsAMissingStore() throws Exception {
        mockMvc.perform(post("/api/v1/retail/replenishment-runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runId\":\"run-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listsRuns() throws Exception {
        when(runService.list(null, null, 50))
                .thenReturn(new PageResponse<>(List.of(runView()), null, 50));

        mockMvc.perform(get("/api/v1/retail/replenishment-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].runId").value("run-1"))
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(jsonPath("$.nextPageToken").isEmpty());
    }

    @Test
    void rejectsAMalformedRunPageToken() throws Exception {
        when(runService.list(null, "not-a-token", 50))
                .thenThrow(new InvalidRequestException(
                        "pageToken is malformed; use the nextPageToken from a previous response"));

        mockMvc.perform(get("/api/v1/retail/replenishment-runs")
                        .param("pageToken", "not-a-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void getsOneRun() throws Exception {
        when(runService.get("run-1")).thenReturn(runView());

        mockMvc.perform(get("/api/v1/retail/replenishment-runs/run-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"));
    }

    @Test
    void listsProposals() throws Exception {
        when(runService.proposals("run-1", null, 50))
                .thenReturn(new PageResponse<>(List.of(proposalView()), "MQ", 50));

        mockMvc.perform(get("/api/v1/retail/replenishment-runs/run-1/proposals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value("SKU-0001"))
                .andExpect(jsonPath("$.items[0].recommendedUnits").value(24))
                .andExpect(jsonPath("$.nextPageToken").value("MQ"));
    }

    private static ReplenishmentRunView runView() {
        return new ReplenishmentRunView(
                "run-1", "ST-001", LocalDate.of(2026, 11, 9), "COMPLETED",
                28, 14, "tester",
                Instant.parse("2026-11-09T12:00:00Z"),
                Instant.parse("2026-11-09T12:00:00Z"),
                Instant.parse("2026-11-09T12:00:01Z"),
                2, "102.00", null);
    }

    private static ReplenishmentProposalView proposalView() {
        return new ReplenishmentProposalView(
                "run-1", LocalDate.of(2026, 11, 9), "ST-001", "SKU-0001",
                "SKU-0001", "Coffee 1", "Beverages", "SUP-001", "Supplier 1",
                52, 4.2, 12.3, 5, 60, 24, "102.00", "BELOW_REORDER_POINT",
                "PROPOSED", 0, null, null, null, null);
    }
}
