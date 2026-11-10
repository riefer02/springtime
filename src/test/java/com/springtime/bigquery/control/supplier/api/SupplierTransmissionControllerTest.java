package com.springtime.bigquery.control.supplier.api;

import com.springtime.bigquery.ServletSliceTestConfiguration;
import com.springtime.bigquery.error.InvalidRequestException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.control.supplier.SupplierTransmissionService;
import com.springtime.bigquery.control.supplier.dto.SupplierTransmissionView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SupplierTransmissionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ServletSliceTestConfiguration.class)
class SupplierTransmissionControllerTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplierTransmissionService service;

    @Test
    void enqueuesATransmissionWith202() throws Exception {
        when(service.enqueueForPo(anyString(), anyString())).thenReturn(view("PENDING"));

        mockMvc.perform(post("/api/v1/retail/purchase-orders/po-1/transmit"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void listsTransmissions() throws Exception {
        when(service.list(null, null, 50))
                .thenReturn(new PageResponse<>(List.of(view("SENT")), "MQ", 50));

        mockMvc.perform(get("/api/v1/retail/supplier-transmissions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("SENT"))
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(jsonPath("$.nextPageToken").value("MQ"));
    }

    @Test
    void rejectsAMalformedPageToken() throws Exception {
        when(service.list(null, "not-a-token", 50))
                .thenThrow(new InvalidRequestException(
                        "pageToken is malformed; use the nextPageToken from a previous response"));

        mockMvc.perform(get("/api/v1/retail/supplier-transmissions")
                        .param("pageToken", "not-a-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void retriesAndAcknowledges() throws Exception {
        when(service.retry(eq(ID), anyString())).thenReturn(view("PENDING"));
        when(service.acknowledge(eq(ID), any(), anyString())).thenReturn(view("ACKED"));

        mockMvc.perform(post("/api/v1/retail/supplier-transmissions/" + ID + "/retry"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/api/v1/retail/supplier-transmissions/" + ID + "/ack")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"supplierReference\":\"SUP-ACK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKED"));
    }

    private static SupplierTransmissionView view(String status) {
        return new SupplierTransmissionView(
                ID, "po-1", "SUP-001", status, 1,
                Instant.parse("2026-11-09T12:00:00Z"), null, null,
                Instant.parse("2026-11-09T12:00:00Z"),
                Instant.parse("2026-11-09T12:00:00Z"), null, null, 0);
    }
}
