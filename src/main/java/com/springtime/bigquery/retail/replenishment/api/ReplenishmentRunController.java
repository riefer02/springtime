package com.springtime.bigquery.retail.replenishment.api;

import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentProposalView;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentRunRequest;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentRunView;
import com.springtime.bigquery.retail.replenishment.application.ReplenishmentRunService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Retail Replenishment Runs", description = "Durable replenishment decision runs and proposals")
@RestController
@RequestMapping(value = "/api/v1/retail/replenishment-runs", produces = MediaType.APPLICATION_JSON_VALUE)
public class ReplenishmentRunController {

    private final ReplenishmentRunService runService;

    public ReplenishmentRunController(ReplenishmentRunService runService) {
        this.runService = runService;
    }

    @Operation(operationId = "createReplenishmentRun",
            summary = "Create or replace a bounded single-store replenishment run")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ReplenishmentRunView create(@Valid @RequestBody ReplenishmentRunRequest request,
                                       Authentication authentication) {
        return runService.create(request, authentication == null ? "-" : authentication.getName());
    }

    @Operation(operationId = "listReplenishmentRuns",
            summary = "List recent replenishment runs, one page at a time")
    @GetMapping
    public PageResponse<ReplenishmentRunView> list(
            @Parameter(description = "Optional store id") @RequestParam(required = false) String storeId,
            @Parameter(description = "Opaque token from a previous response")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "Rows per page; clamped to the server ceiling")
            @RequestParam(defaultValue = "${retail.default-page-size:50}") int limit) {
        return runService.list(storeId, pageToken, limit);
    }

    @Operation(operationId = "getReplenishmentRun", summary = "Get one replenishment run")
    @GetMapping("/{runId}")
    public ReplenishmentRunView get(@PathVariable String runId) {
        return runService.get(runId);
    }

    @Operation(operationId = "listReplenishmentProposals",
            summary = "List prioritized proposals for one run, one page at a time")
    @GetMapping("/{runId}/proposals")
    public PageResponse<ReplenishmentProposalView> proposals(
            @PathVariable String runId,
            @Parameter(description = "Opaque token from a previous response")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "Rows per page; clamped to the server ceiling")
            @RequestParam(defaultValue = "${retail.default-page-size:50}") int limit) {
        return runService.proposals(runId, pageToken, limit);
    }
}
