package com.springtime.bigquery.retail.replenishment.application;

import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.control.run.RunLease;
import com.springtime.bigquery.error.BusinessRuleException;
import com.springtime.bigquery.error.ConflictException;
import com.springtime.bigquery.error.InvalidQueryException;
import com.springtime.bigquery.error.ResourceNotFoundException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.PageToken;
import com.springtime.bigquery.retail.query.RetailQueryPort;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentProposalView;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentRunRequest;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentRunView;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRun;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRunStatus;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRunSummary;
import com.springtime.bigquery.util.SafeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Creates bounded single-store replenishment runs. The write sequence is:
 * begin run -> clear old proposals -> merge proposals -> summarize -> complete.
 * A failure marks the run FAILED; re-running the same runId replaces it.
 */
@Service
public class ReplenishmentRunService {

    private static final Logger log = LoggerFactory.getLogger(ReplenishmentRunService.class);
    private static final int MAX_PROPOSALS = 5_000;
    private static final int MAX_LIST_SIZE = 200;
    private static final Duration RUN_LEASE_TTL = Duration.ofMinutes(30);

    private final ReplenishmentRunRepository repository;
    private final RetailQueryPort retailQueryPort;
    private final RetailProperties properties;
    private final ReplenishmentMetrics metrics;
    private final RunLease runLease;
    private final Clock clock;

    public ReplenishmentRunService(ReplenishmentRunRepository repository,
                                   RetailQueryPort retailQueryPort,
                                   RetailProperties properties,
                                   ReplenishmentMetrics metrics,
                                   RunLease runLease,
                                   Clock clock) {
        this.repository = repository;
        this.retailQueryPort = retailQueryPort;
        this.properties = properties;
        this.metrics = metrics;
        this.runLease = runLease;
        this.clock = clock;
    }

    public ReplenishmentRunView create(ReplenishmentRunRequest request, String requestedBy) {
        String runId = request.runId() == null || request.runId().isBlank()
                ? UUID.randomUUID().toString()
                : request.runId();
        if (!retailQueryPort.storeExists(request.storeId())) {
            throw new ResourceNotFoundException("Store " + request.storeId() + " was not found");
        }
        LocalDate asOf = request.asOfDate() == null
                ? retailQueryPort.latestSnapshotDate()
                : request.asOfDate();
        if (asOf.isAfter(properties.today(clock))) {
            throw new InvalidQueryException("asOfDate must not be in the future");
        }
        int demandDays = clampDays(request.demandWindowDays(), properties.demandWindowDays());
        int targetDays = clampDays(request.targetCoverDays(), properties.targetCoverDays());
        Instant now = clock.instant();
        String requester = SafeLog.id(requestedBy);

        ReplenishmentRun running = new ReplenishmentRun(
                runId, request.storeId(), asOf, ReplenishmentRunStatus.RUNNING,
                demandDays, targetDays, requester, now, now, null, null, null, null);
        String leaseOwner = UUID.randomUUID().toString();
        if (!runLease.tryAcquire(runId, leaseOwner, RUN_LEASE_TTL)) {
            throw new ConflictException(
                    "Replenishment run " + runId + " is already being created; retry shortly");
        }
        boolean began = false;
        try {
            if (repository.hasDecisions(runId)) {
                throw new ConflictException("Run " + runId + " already has decisions; use a new runId");
            }
            repository.begin(running);
            began = true;
            repository.clearProposals(runId);
            repository.mergeProposals(running, MAX_PROPOSALS);
            ReplenishmentRunSummary summary = repository.summary(runId);
            Instant completedAt = clock.instant();
            repository.complete(runId, summary, completedAt);
            metrics.recordRun("COMPLETED", summary.proposalCount());
            return ReplenishmentViewMapper.runView(new ReplenishmentRun(
                    runId, request.storeId(), asOf, ReplenishmentRunStatus.COMPLETED,
                    demandDays, targetDays, requester, now, now, completedAt,
                    summary.proposalCount(), summary.totalOrderValue(), null));
        } catch (RuntimeException exception) {
            if (began) {
                markFailed(runId, exception);
            }
            throw exception;
        } finally {
            runLease.release(runId, leaseOwner);
        }
    }

    public ReplenishmentRunView get(String runId) {
        return repository.find(runId)
                .map(ReplenishmentViewMapper::runView)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Replenishment run " + runId + " was not found"));
    }

    public PageResponse<ReplenishmentRunView> list(String storeId, String pageToken, int limit) {
        int pageSize = Math.min(Math.max(1, limit), MAX_LIST_SIZE);
        int offset = PageToken.decodeOffset(pageToken);
        List<ReplenishmentRunView> fetched = repository.list(storeId, pageSize + 1, offset).stream()
                .map(ReplenishmentViewMapper::runView)
                .toList();
        return PageToken.page(fetched, offset, pageSize);
    }

    public PageResponse<ReplenishmentProposalView> proposals(String runId, String pageToken,
                                                             int limit) {
        get(runId); // 404 before querying proposals
        int pageSize = Math.min(Math.max(1, limit), MAX_PROPOSALS);
        int offset = PageToken.decodeOffset(pageToken);
        List<ReplenishmentProposalView> fetched =
                repository.proposals(runId, pageSize + 1, offset).stream()
                        .map(ReplenishmentViewMapper::proposalView)
                        .toList();
        return PageToken.page(fetched, offset, pageSize);
    }

    private void markFailed(String runId, RuntimeException exception) {
        try {
            repository.fail(runId, exception.getClass().getSimpleName(), clock.instant());
        } catch (RuntimeException markFailure) {
            log.error("Unable to mark replenishment run {} as failed", SafeLog.id(runId), markFailure);
        }
        metrics.recordRun("FAILED", 0);
    }

    private static int clampDays(Integer requested, int fallback) {
        int value = requested == null ? fallback : requested;
        return Math.min(Math.max(value, 1), 365);
    }

}
