package com.springtime.bigquery.api;

import com.springtime.bigquery.model.query.QueryResultPage;
import com.springtime.bigquery.service.BigQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Jobs", description = "Fetch additional pages of results for an existing query job")
@RestController
@RequestMapping(value = "/api/v1/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
public class JobController {

    private final BigQueryService bigQueryService;

    public JobController(BigQueryService bigQueryService) {
        this.bigQueryService = bigQueryService;
    }

    @Operation(operationId = "getJobResults",
            summary = "Get a page of results for a query job",
            description = "Use the nextPageToken returned by the initial query or a previous page.")
    @GetMapping("/{jobId}/results")
    public QueryResultPage results(
            @PathVariable String jobId,
            @Parameter(description = "Opaque token from a previous response")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "Rows per page; clamped to the server ceiling")
            @RequestParam(defaultValue = "${app.pagination.default-page-size:100}") int pageSize) {
        return bigQueryService.queryResults(jobId, pageToken, pageSize);
    }
}
