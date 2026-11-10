package com.springtime.bigquery.api;

import com.springtime.bigquery.ServletSliceTestConfiguration;
import com.springtime.bigquery.model.query.Column;
import com.springtime.bigquery.model.query.QueryResultPage;
import com.springtime.bigquery.service.BigQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = JobController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ServletSliceTestConfiguration.class)
class JobControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private BigQueryService bigQueryService;

    @Test
    void returnsAPageOfJobResults() throws Exception {
        when(bigQueryService.queryResults("job-1", "token", 50)).thenReturn(new QueryResultPage(
                "job-1",
                List.of(new Column("one", "INT64", "NULLABLE")),
                List.of(Map.of("one", 1)),
                1,
                10L,
                "next-token", false));

        mockMvc.perform(get("/api/v1/jobs/job-1/results?pageToken=token&pageSize=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value("job-1"))
                .andExpect(jsonPath("$.rows[0].one").value(1))
                .andExpect(jsonPath("$.totalRows").value(10))
                .andExpect(jsonPath("$.nextPageToken").value("next-token"));
    }
}
