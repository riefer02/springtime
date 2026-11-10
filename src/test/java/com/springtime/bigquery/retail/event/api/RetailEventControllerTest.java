package com.springtime.bigquery.retail.event.api;

import com.springtime.bigquery.ServletSliceTestConfiguration;
import com.springtime.bigquery.error.InvalidEventException;
import com.springtime.bigquery.messaging.MessagingException;
import com.springtime.bigquery.retail.event.dto.AcceptedEvent;
import com.springtime.bigquery.retail.event.application.RetailEventPublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = RetailEventController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ServletSliceTestConfiguration.class)
class RetailEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RetailEventPublisher publisher;

    @Test
    void acceptsARetailEvent() throws Exception {
        when(publisher.publish(any())).thenReturn(
                AcceptedEvent.accepted("evt-1", "msg-1", "retail-events"));

        mockMvc.perform(post("/api/v1/retail/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-1",
                                  "eventType": "SALE",
                                  "occurredAt": "2026-11-09T12:00:00Z",
                                  "storeId": "ST-001",
                                  "productId": "SKU-0001",
                                  "quantity": 2
                                }"""))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void mapsABrokerOutageToARetryable503() throws Exception {
        when(publisher.publish(any()))
                .thenThrow(new MessagingException("Local broker is full for topic retail-events"));

        mockMvc.perform(post("/api/v1/retail/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-broker-full",
                                  "eventType": "SALE",
                                  "occurredAt": "2026-11-09T12:00:00Z",
                                  "storeId": "ST-001",
                                  "productId": "SKU-0001",
                                  "quantity": 2
                                }"""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.retryable").value(true));
    }

    @Test
    void rejectsAnEventWithoutId() throws Exception {
        mockMvc.perform(post("/api/v1/retail/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventType": "SALE",
                                  "occurredAt": "2026-11-09T12:00:00Z",
                                  "storeId": "ST-001"
                                }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void mapsSemanticRejectionToInvalidEvent() throws Exception {
        when(publisher.publish(any()))
                .thenThrow(new InvalidEventException("SALE quantity must be positive"));

        mockMvc.perform(post("/api/v1/retail/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "eventId": "evt-1",
                                  "eventType": "SALE",
                                  "occurredAt": "2026-11-09T12:00:00Z",
                                  "storeId": "ST-001",
                                  "productId": "SKU-0001",
                                  "quantity": 0
                                }"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_EVENT"));
    }
}
