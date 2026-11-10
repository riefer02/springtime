package com.springtime.bigquery.retail.inventory.api;

import com.springtime.bigquery.retail.inventory.application.InventoryProjectionService;
import com.springtime.bigquery.retail.inventory.model.ProjectionMode;
import com.springtime.bigquery.retail.inventory.model.ProjectionResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Retail Inventory", description = "Inventory movement ledger and reconciliation")
@RestController
@RequestMapping(value = "/api/v1/retail/inventory/projection", produces = MediaType.APPLICATION_JSON_VALUE)
public class InventoryProjectionController {

    private final InventoryProjectionService projectionService;

    public InventoryProjectionController(InventoryProjectionService projectionService) {
        this.projectionService = projectionService;
    }

    @Operation(operationId = "refreshInventoryProjection",
            summary = "Rebuild or incrementally refresh the on-hand projection")
    @PostMapping
    public ProjectionResult refresh(
            @Parameter(description = "INCREMENTAL or FULL") @RequestParam(defaultValue = "INCREMENTAL")
            ProjectionMode mode,
            @Parameter(description = "Days to recompute for late events") @RequestParam(defaultValue = "7")
            int lookbackDays) {
        return mode == ProjectionMode.FULL
                ? projectionService.rebuild()
                : projectionService.incremental(lookbackDays);
    }
}
