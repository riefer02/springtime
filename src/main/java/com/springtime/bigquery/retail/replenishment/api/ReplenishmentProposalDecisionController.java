package com.springtime.bigquery.retail.replenishment.api;

import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.retail.replenishment.dto.ProposalDecisionRequest;
import com.springtime.bigquery.retail.replenishment.dto.ProposalDecisionView;
import com.springtime.bigquery.retail.replenishment.dto.ProposalOrderRequest;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentProposalView;
import com.springtime.bigquery.retail.replenishment.application.ProposalDecisionService;
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
@RequestMapping(value = "/api/v1/retail/replenishment-runs/{runId}/proposals/{productId}",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class ReplenishmentProposalDecisionController {

    private final ProposalDecisionService decisionService;

    public ReplenishmentProposalDecisionController(ProposalDecisionService decisionService) {
        this.decisionService = decisionService;
    }

    @Operation(operationId = "approveReplenishmentProposal", summary = "Approve a proposal")
    @PostMapping("/approve")
    public ReplenishmentProposalView approve(@PathVariable String runId,
                                             @PathVariable String productId,
                                             @Valid @RequestBody ProposalDecisionRequest request,
                                             Authentication authentication) {
        return decisionService.approve(runId, productId, request.expectedVersion(),
                request.note(), actor(authentication));
    }

    @Operation(operationId = "rejectReplenishmentProposal", summary = "Reject a proposal")
    @PostMapping("/reject")
    public ReplenishmentProposalView reject(@PathVariable String runId,
                                            @PathVariable String productId,
                                            @Valid @RequestBody ProposalDecisionRequest request,
                                            Authentication authentication) {
        return decisionService.reject(runId, productId, request.expectedVersion(),
                request.note(), actor(authentication));
    }

    @Operation(operationId = "orderReplenishmentProposal",
            summary = "Mark an approved proposal ordered with an external reference")
    @PostMapping("/order")
    public ReplenishmentProposalView order(@PathVariable String runId,
                                           @PathVariable String productId,
                                           @Valid @RequestBody ProposalOrderRequest request,
                                           Authentication authentication) {
        return decisionService.order(runId, productId, request.expectedVersion(),
                request.note(), request.orderReference(), actor(authentication));
    }

    @Operation(operationId = "listProposalDecisions",
            summary = "Proposal decision history, one page at a time")
    @GetMapping("/history")
    public PageResponse<ProposalDecisionView> history(@PathVariable String runId,
                                                      @PathVariable String productId,
                                                      @Parameter(description = "Opaque token from a previous response")
                                                      @RequestParam(required = false) String pageToken,
                                                      @Parameter(description = "Rows per page; clamped to the server ceiling")
                                                      @RequestParam(defaultValue = "${retail.default-page-size:50}") int limit) {
        return decisionService.history(runId, productId, pageToken, limit);
    }

    private static String actor(Authentication authentication) {
        return authentication == null ? "-" : authentication.getName();
    }
}
