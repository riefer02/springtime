package com.springtime.bigquery.retail.inventory.api;

import com.springtime.bigquery.ServletSliceTestConfiguration;
import com.springtime.bigquery.retail.inventory.application.InventoryProjectionService;
import com.springtime.bigquery.retail.inventory.model.ProjectionResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = InventoryProjectionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(ServletSliceTestConfiguration.class)
class InventoryProjectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InventoryProjectionService projectionService;

    @Test
    void rebuildsInFullMode() throws Exception {
        when(projectionService.rebuild()).thenReturn(new ProjectionResult(
                "REBUILT", LocalDate.of(2026, 8, 12), LocalDate.of(2026, 11, 9),
                240, Instant.parse("2026-11-09T12:00:00Z")));

        mockMvc.perform(post("/api/v1/retail/inventory/projection").param("mode", "FULL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REBUILT"))
                .andExpect(jsonPath("$.affectedRows").value(240));
    }

    @Test
    void refreshesIncrementallyByDefault() throws Exception {
        when(projectionService.incremental(7)).thenReturn(new ProjectionResult(
                "INCREMENTAL", LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 9),
                100, Instant.parse("2026-11-09T12:00:00Z")));

        mockMvc.perform(post("/api/v1/retail/inventory/projection"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INCREMENTAL"))
                .andExpect(jsonPath("$.affectedRows").value(100));
    }
}
