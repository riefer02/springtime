package com.springtime.bigquery.api;

import com.springtime.bigquery.model.query.DatasetSummary;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.TableSchema;
import com.springtime.bigquery.model.query.TableSummary;
import com.springtime.bigquery.service.CatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Catalog", description = "Browse datasets, tables, and nested table schemas")
@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Operation(operationId = "listDatasets", summary = "List visible datasets, one page at a time")
    @GetMapping("/datasets")
    public PageResponse<DatasetSummary> datasets(
            @Parameter(description = "Opaque token from a previous response")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "Rows per page; clamped to the server ceiling")
            @RequestParam(defaultValue = "${app.pagination.default-page-size:100}") int pageSize) {
        return catalogService.listDatasets(pageToken, pageSize);
    }

    @Operation(operationId = "listTables", summary = "List tables in a dataset, one page at a time")
    @GetMapping("/datasets/{datasetId}/tables")
    public PageResponse<TableSummary> tables(
            @PathVariable String datasetId,
            @RequestParam(required = false) String pageToken,
            @RequestParam(defaultValue = "${app.pagination.default-page-size:100}") int pageSize) {
        return catalogService.listTables(datasetId, pageToken, pageSize);
    }

    @Operation(operationId = "getTableSchema", summary = "Get a table schema, including nested fields")
    @GetMapping("/datasets/{datasetId}/tables/{tableId}/schema")
    public TableSchema schema(@PathVariable String datasetId, @PathVariable String tableId) {
        return catalogService.getTableSchema(datasetId, tableId);
    }
}
