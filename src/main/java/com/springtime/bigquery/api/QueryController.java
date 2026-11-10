package com.springtime.bigquery.api;

import com.springtime.bigquery.model.query.QueryRequest;
import com.springtime.bigquery.model.query.QueryResponse;
import com.springtime.bigquery.service.BigQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Queries", description = "Execute or dry-run a single BigQuery SQL statement")
@RestController
@RequestMapping(value = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class QueryController {

    private final BigQueryService bigQueryService;

    public QueryController(BigQueryService bigQueryService) {
        this.bigQueryService = bigQueryService;
    }

    @Operation(operationId = "executeQuery",
            summary = "Execute or dry-run a SQL statement",
            description = "Returns the first page of rows plus job statistics. Dry runs are free.")
    @PostMapping(value = "/query", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<QueryResponse> query(@Valid @RequestBody QueryRequest request) {
        QueryResponse response = bigQueryService.execute(request);
        return ResponseEntity.ok()
                .header("X-BigQuery-Job-Id", response.jobId())
                .body(response);
    }
}
