package com.springtime.bigquery.api;

import com.springtime.bigquery.ServletSliceTestConfiguration;
import com.springtime.bigquery.model.query.DatasetSummary;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.TableSummary;
import com.springtime.bigquery.error.ResourceNotFoundException;
import com.springtime.bigquery.service.CatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = CatalogController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ServletSliceTestConfiguration.class)
class CatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogService catalogService;

    @Test
    void listsDatasetsWithPaginationEnvelope() throws Exception {
        when(catalogService.listDatasets(isNull(), eq(100))).thenReturn(new PageResponse<>(
                List.of(new DatasetSummary("p", "analytics", "Analytics", "US")), "next-token", 100));

        mockMvc.perform(get("/api/v1/datasets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].datasetId").value("analytics"))
                .andExpect(jsonPath("$.items[0].location").value("US"))
                .andExpect(jsonPath("$.nextPageToken").value("next-token"))
                .andExpect(jsonPath("$.pageSize").value(100));
    }

    @Test
    void forwardsPageTokenAndSize() throws Exception {
        when(catalogService.listDatasets(eq("abc"), eq(2)))
                .thenReturn(new PageResponse<>(List.of(), null, 2));

        mockMvc.perform(get("/api/v1/datasets?pageToken=abc&pageSize=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextPageToken").doesNotExist());

        verify(catalogService).listDatasets("abc", 2);
    }

    @Test
    void listsTablesWithIsoTimestamps() throws Exception {
        when(catalogService.listTables(eq("analytics"), isNull(), eq(100))).thenReturn(new PageResponse<>(
                List.of(new TableSummary("events", "TABLE", Instant.parse("2024-01-01T00:00:00Z"))),
                null, 100));

        mockMvc.perform(get("/api/v1/datasets/analytics/tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].tableId").value("events"))
                .andExpect(jsonPath("$.items[0].created").value("2024-01-01T00:00:00Z"));
    }

    @Test
    void rejectsInvalidPageSizeAsClientError() throws Exception {
        mockMvc.perform(get("/api/v1/datasets?pageSize=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsNotFoundForMissingTable() throws Exception {
        when(catalogService.getTableSchema("analytics", "missing"))
                .thenThrow(new ResourceNotFoundException("Table p.analytics.missing was not found"));

        mockMvc.perform(get("/api/v1/datasets/analytics/tables/missing/schema"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not found"));
    }
}
