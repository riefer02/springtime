package com.springtime.bigquery.retail.replenishment.api;

import com.springtime.bigquery.ServletSliceTestConfiguration;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.retail.replenishment.application.ReplenishmentRunService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the configured retail page size reaches the service, not a hard-coded literal in the
 * controller. Before this, only the read endpoints honoured {@code RETAIL_DEFAULT_PAGE_SIZE}.
 */
@WebMvcTest(controllers = ReplenishmentRunController.class,
        properties = "retail.default-page-size=25")
@AutoConfigureMockMvc(addFilters = false)
@Import(ServletSliceTestConfiguration.class)
class ConfiguredPageSizeTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReplenishmentRunService runService;

    @Test
    void listUsesTheConfiguredDefaultPageSize() throws Exception {
        when(runService.list(isNull(), isNull(), eq(25)))
                .thenReturn(new PageResponse<>(List.of(), null, 25));

        mockMvc.perform(get("/api/v1/retail/replenishment-runs"))
                .andExpect(status().isOk());

        verify(runService).list(isNull(), isNull(), eq(25));
    }
}
