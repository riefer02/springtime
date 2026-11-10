package com.springtime.bigquery.retail.api;

import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.retail.model.InventoryPosition;
import com.springtime.bigquery.retail.model.ReplenishmentRecommendation;
import com.springtime.bigquery.retail.model.RetailDashboard;
import com.springtime.bigquery.retail.model.RetailPage;
import com.springtime.bigquery.retail.model.StoreSummary;
import com.springtime.bigquery.retail.model.RiskLevel;
import com.springtime.bigquery.retail.service.RetailService;
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

@Tag(name = "Retail", description = "Generic retail inventory, replenishment, and dashboard analytics")
@RestController
@RequestMapping(value = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
public class RetailController {

    private final RetailService retailService;

    public RetailController(RetailService retailService) {
        this.retailService = retailService;
    }

    @Operation(operationId = "listRetailStores",
            summary = "List active stores, one page at a time")
    @GetMapping("/stores")
    public PageResponse<StoreSummary> stores(
            @Parameter(description = "Opaque token from a previous response")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "Rows per page; clamped to the server ceiling")
            @RequestParam(defaultValue = "${retail.default-page-size:50}") int pageSize) {
        return retailService.stores(pageToken, pageSize);
    }

    @Operation(operationId = "getInventory",
            summary = "Inventory positions with demand cover and stockout risk for one store")
    @GetMapping("/inventory")
    public RetailPage<InventoryPosition> inventory(
            @Parameter(description = "Store id") @RequestParam String storeId,
            @Parameter(description = "Business date; defaults to the latest snapshot")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @Parameter(description = "Optional category filter")
            @RequestParam(required = false) String category,
            @Parameter(description = "Optional risk band filter")
            @RequestParam(required = false) RiskLevel riskLevel,
            @RequestParam(defaultValue = "${retail.default-page-size:50}") int pageSize) {
        return retailService.inventory(storeId, asOfDate, category, riskLevel, pageSize);
    }

    @Operation(operationId = "getReplenishment",
            summary = "Prioritized replenishment recommendations for one store")
    @GetMapping("/replenishment")
    public RetailPage<ReplenishmentRecommendation> replenishment(
            @Parameter(description = "Store id") @RequestParam String storeId,
            @Parameter(description = "Business date; defaults to the latest snapshot")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate,
            @RequestParam(defaultValue = "${retail.default-page-size:50}") int pageSize) {
        return retailService.replenishment(storeId, asOfDate, pageSize);
    }

    @Operation(operationId = "getRetailDashboard",
            summary = "Headline inventory, sales, shipment, and promotion KPIs for one store")
    @GetMapping("/dashboard")
    public RetailDashboard dashboard(
            @Parameter(description = "Store id") @RequestParam String storeId,
            @Parameter(description = "Business date; defaults to the latest snapshot")
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOfDate) {
        return retailService.dashboard(storeId, asOfDate);
    }
}
