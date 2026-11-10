package com.springtime.bigquery.api;

import com.springtime.bigquery.ServletSliceTestConfiguration;
import com.springtime.bigquery.model.query.Column;
import com.springtime.bigquery.model.query.QueryResponse;
import com.springtime.bigquery.error.InvalidQueryException;
import com.google.cloud.bigquery.BigQueryException;
import com.springtime.bigquery.service.BigQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = QueryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ServletSliceTestConfiguration.class)
class QueryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BigQueryService bigQueryService;

    @Test
    void mapsATransientUpstreamOutageTo503WithSafeMethodRetry() throws Exception {
        when(bigQueryService.execute(any()))
                .thenThrow(new BigQueryException(503, "backend error"));

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sql": "SELECT 1"}"""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("UPSTREAM_UNAVAILABLE"))
                // POST is not a safe method, so the generic retry policy asks the client to
                // decide; a GET-style job-results retry stays automatically retryable.
                .andExpect(jsonPath("$.retryable").value(false));
    }

    @Test
    void returnsRowsAndJobHeader() throws Exception {
        when(bigQueryService.execute(any())).thenReturn(new QueryResponse(
                "job-1",
                List.of(new Column("one", "INT64", "NULLABLE")),
                List.of(Map.of("one", 1)),
                1,
                1L,
                0L,
                0L,
                false,
                false,
                "next-token", false));

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sql": "SELECT 1 AS one"}"""))
                .andExpect(status().isOk())
                .andExpect(header().string("X-BigQuery-Job-Id", "job-1"))
                .andExpect(jsonPath("$.rows[0].one").value(1))
                .andExpect(jsonPath("$.returnedRows").value(1))
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.nextPageToken").value("next-token"));
    }

    @Test
    void rejectsMissingSql() throws Exception {
        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("sql"))
                .andExpect(jsonPath("$.errors[0].message").value("sql is required"));
    }

    @Test
    void mapsPolicyViolationsToBadRequest() throws Exception {
        when(bigQueryService.execute(any()))
                .thenThrow(new InvalidQueryException("Only read-only statements are allowed"));

        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sql": "DELETE FROM t"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid query"))
                .andExpect(jsonPath("$.detail").value("Only read-only statements are allowed"));
    }

    @Test
    void rejectsMalformedJson() throws Exception {
        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsUnknownParameterTypesAtTheApiBoundary() throws Exception {
        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sql": "SELECT @x", "parameters": [{"name": "x", "type": "NOT_A_TYPE", "value": 1}]}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void rejectsUnknownPropertiesToPreventSilentTypos() throws Exception {
        mockMvc.perform(post("/api/v1/query")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sql": "SELECT 1", "dryrun": true}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"));
    }
}
