package com.springtime.bigquery.retail.inventory.api;

import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.retail.inventory.application.InventoryService;
import com.springtime.bigquery.retail.inventory.model.InventoryMovementView;
import com.springtime.bigquery.retail.inventory.model.ReconciliationReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@Tag(name = "Retail Inventory", description = "Inventory movement ledger and reconciliation")
@RestController
@RequestMapping(value = "/api/v1/retail/inventory", produces = MediaType.APPLICATION_JSON_VALUE)
public class InventoryController {

    private final InventoryService inventoryService;

    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @Operation(operationId = "listInventoryMovements",
            summary = "List ledger movements for a store/product and date range, one page at a time")
    @GetMapping("/movements")
    public PageResponse<InventoryMovementView> movements(
            @Parameter(description = "Optional store id") @RequestParam(required = false) String storeId,
            @Parameter(description = "Optional product id") @RequestParam(required = false) String productId,
            @Parameter(description = "Inclusive start date; defaults to seven days before the end")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Inclusive end date; defaults to the latest snapshot")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Opaque token from a previous response")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "Rows per page; clamped to the server ceiling")
            @RequestParam(defaultValue = "${retail.default-page-size:50}") int limit) {
        return inventoryService.movements(storeId, productId, from, to, pageToken, limit);
    }

    @Operation(operationId = "reconcileInventory",
            summary = "Compare the movement ledger with the inventory snapshot for a business date")
    @GetMapping("/reconciliation")
    public ReconciliationReport reconciliation(
            @Parameter(description = "Optional store id") @RequestParam(required = false) String storeId,
            @Parameter(description = "Business date; defaults to the latest snapshot")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @Parameter(description = "Allowed absolute drift per store/product; defaults to config")
            @RequestParam(required = false) Long tolerance,
            @RequestParam(defaultValue = "${retail.default-page-size:50}") int limit) {
        return inventoryService.reconciliation(storeId, asOfDate, tolerance, limit);
    }
}
