package com.springtime.bigquery.retail.replenishment.api;

import com.springtime.bigquery.ServletSliceTestConfiguration;
import com.springtime.bigquery.error.InvalidRequestException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.retail.replenishment.dto.ProposalDecisionView;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentProposalView;
import com.springtime.bigquery.retail.replenishment.application.ProposalDecisionService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ReplenishmentProposalDecisionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ServletSliceTestConfiguration.class)
class ReplenishmentProposalDecisionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProposalDecisionService decisionService;

    @Test
    void approvesAProposal() throws Exception {
        when(decisionService.approve(anyString(), anyString(), anyInt(), any(), anyString()))
                .thenReturn(proposalView("APPROVED", 1, null));

        mockMvc.perform(post("/api/v1/retail/replenishment-runs/run-1/proposals/SKU-0001/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"note\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void rejectsAProposal() throws Exception {
        when(decisionService.reject(anyString(), anyString(), anyInt(), any(), anyString()))
                .thenReturn(proposalView("REJECTED", 1, null));

        mockMvc.perform(post("/api/v1/retail/replenishment-runs/run-1/proposals/SKU-0001/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void marksAProposalOrdered() throws Exception {
        when(decisionService.order(anyString(), anyString(), anyInt(), any(),
                anyString(), anyString())).thenReturn(proposalView("ORDERED", 2, "PO-1"));

        mockMvc.perform(post("/api/v1/retail/replenishment-runs/run-1/proposals/SKU-0001/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"orderReference\":\"PO-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ORDERED"))
                .andExpect(jsonPath("$.orderReference").value("PO-1"));
    }

    @Test
    void rejectsAnOrderWithoutAReference() throws Exception {
        mockMvc.perform(post("/api/v1/retail/replenishment-runs/run-1/proposals/SKU-0001/order")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void listsDecisionHistory() throws Exception {
        when(decisionService.history("run-1", "SKU-0001", null, 50)).thenReturn(
                new PageResponse<>(List.of(
                        new ProposalDecisionView("d-1", "run-1", "ST-001", "SKU-0001",
                                "PROPOSED", "APPROVED", 0, "planner", "ok", null,
                                Instant.parse("2026-11-09T12:00:00Z"))), null, 50));

        mockMvc.perform(get("/api/v1/retail/replenishment-runs/run-1/proposals/SKU-0001/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].toStatus").value("APPROVED"))
                .andExpect(jsonPath("$.nextPageToken").isEmpty());
    }

    @Test
    void rejectsAMalformedHistoryPageToken() throws Exception {
        when(decisionService.history("run-1", "SKU-0001", "not-a-token", 50))
                .thenThrow(new InvalidRequestException(
                        "pageToken is malformed; use the nextPageToken from a previous response"));

        mockMvc.perform(get("/api/v1/retail/replenishment-runs/run-1/proposals/SKU-0001/history")
                        .param("pageToken", "not-a-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private static ReplenishmentProposalView proposalView(String status, int version, String orderReference) {
        return new ReplenishmentProposalView(
                "run-1", LocalDate.of(2026, 11, 9), "ST-001", "SKU-0001",
                "SKU-0001", "Coffee 1", "Beverages", "SUP-001", "Supplier 1",
                52, 4.2, 12.3, 5, 60, 24, "102.00", "BELOW_REORDER_POINT",
                status, version, null, "planner", null, orderReference);
    }
}
