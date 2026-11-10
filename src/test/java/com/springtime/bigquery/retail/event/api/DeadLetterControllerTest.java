package com.springtime.bigquery.retail.event.api;

import com.springtime.bigquery.ServletSliceTestConfiguration;
import com.springtime.bigquery.error.InvalidRequestException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.retail.event.dto.DeadLetterView;
import com.springtime.bigquery.retail.event.dto.ReplayResult;
import com.springtime.bigquery.retail.event.application.DeadLetterReplayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = DeadLetterController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ServletSliceTestConfiguration.class)
class DeadLetterControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeadLetterReplayService replayService;

    @Test
    void listsDeadLetters() throws Exception {
        when(replayService.list(null, 50)).thenReturn(new PageResponse<>(List.of(new DeadLetterView(
                "evt-1", "SALE", "1", Instant.parse("2026-11-09T12:00:00Z"),
                3, "RETRY_EXHAUSTED", "IllegalStateException", "retail-events",
                null, "PENDING")), "MQ", 50));

        mockMvc.perform(get("/api/v1/retail/events/dead-letters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].eventId").value("evt-1"))
                .andExpect(jsonPath("$.items[0].replayStatus").value("PENDING"))
                .andExpect(jsonPath("$.pageSize").value(50))
                .andExpect(jsonPath("$.nextPageToken").value("MQ"));
    }

    @Test
    void rejectsAMalformedPageToken() throws Exception {
        when(replayService.list("not-a-token", 50))
                .thenThrow(new InvalidRequestException(
                        "pageToken is malformed; use the nextPageToken from a previous response"));

        mockMvc.perform(get("/api/v1/retail/events/dead-letters")
                        .param("pageToken", "not-a-token"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void replaysOneDeadLetter() throws Exception {
        when(replayService.replay("evt-1")).thenReturn(
                ReplayResult.replayed("evt-1", "msg-1", Instant.parse("2026-11-09T12:00:00Z")));

        mockMvc.perform(post("/api/v1/retail/events/dead-letters/evt-1/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventId").value("evt-1"))
                .andExpect(jsonPath("$.status").value("REPLAYED"));
    }
}
