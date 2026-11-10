package com.springtime.bigquery.retail.replenishment.application;

import com.springtime.bigquery.control.run.RunLease;
import com.springtime.bigquery.error.BusinessRuleException;
import com.springtime.bigquery.error.ConflictException;
import com.springtime.bigquery.error.ResourceNotFoundException;
import com.springtime.bigquery.error.UpstreamUnavailableException;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.PageToken;
import com.springtime.bigquery.retail.replenishment.dto.ProposalDecisionView;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentProposalView;
import com.springtime.bigquery.retail.replenishment.model.ProposalDecision;
import com.springtime.bigquery.retail.replenishment.model.ProposalStatus;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentProposal;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRun;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRunStatus;
import com.springtime.bigquery.util.Notes;
import com.springtime.bigquery.util.SafeLog;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Proposal state machine with optimistic concurrency and an append-only audit trail. */
@Service
public class ProposalDecisionService {

    private static final int MAX_HISTORY = 200;
    private static final Duration RUN_LEASE_TTL = Duration.ofMinutes(30);

    private final ReplenishmentRunRepository repository;
    private final RunLease runLease;
    private final Clock clock;

    public ProposalDecisionService(ReplenishmentRunRepository repository, RunLease runLease,
                                   Clock clock) {
        this.repository = repository;
        this.runLease = runLease;
        this.clock = clock;
    }

    public ReplenishmentProposalView approve(String runId, String productId,
                                              int expectedVersion, String note, String actor) {
        return transition(runId, productId, ProposalStatus.APPROVED,
                expectedVersion, note, null, actor);
    }

    public ReplenishmentProposalView reject(String runId, String productId,
                                             int expectedVersion, String note, String actor) {
        return transition(runId, productId, ProposalStatus.REJECTED,
                expectedVersion, note, null, actor);
    }

    public ReplenishmentProposalView order(String runId, String productId,
                                            int expectedVersion, String note,
                                            String orderReference, String actor) {
        if (orderReference == null || orderReference.isBlank()) {
            throw new ConflictException("orderReference is required to mark a proposal ordered");
        }
        return transition(runId, productId, ProposalStatus.ORDERED,
                expectedVersion, note, orderReference, actor);
    }

    public PageResponse<ProposalDecisionView> history(String runId, String productId,
                                                      String pageToken, int limit) {
        loadRun(runId);
        loadProposal(runId, productId);
        int pageSize = Math.min(Math.max(1, limit), MAX_HISTORY);
        int offset = PageToken.decodeOffset(pageToken);
        List<ProposalDecisionView> fetched =
                repository.decisions(runId, productId, pageSize + 1, offset).stream()
                        .map(ReplenishmentViewMapper::decisionView)
                        .toList();
        return PageToken.page(fetched, offset, pageSize);
    }

    private ReplenishmentProposalView transition(String runId, String productId,
                                                  ProposalStatus target, int expectedVersion,
                                                  String note, String orderReference, String actor) {
        String leaseOwner = UUID.randomUUID().toString();
        if (!runLease.tryAcquire(runId, leaseOwner, RUN_LEASE_TTL)) {
            throw new ConflictException("Run " + runId + " is being replaced; retry shortly");
        }
        try {
            ReplenishmentRun run = loadRun(runId);
            if (run.status() != ReplenishmentRunStatus.COMPLETED) {
                throw new ConflictException(
                        "Run " + runId + " is not completed; decisions are not accepted");
            }
            ReplenishmentProposal proposal = loadProposal(runId, productId);
            if (proposal.version() != expectedVersion) {
                throw new ConflictException(
                        "Proposal version conflict; current version is " + proposal.version());
            }
            if (proposal.status() == target) {
                if (target == ProposalStatus.ORDERED
                        && !Objects.equals(proposal.orderReference(), orderReference)) {
                    throw new ConflictException("Proposal is already ordered with a different reference");
                }
                return ReplenishmentViewMapper.proposalView(proposal);
            }
            if (!allowed(proposal.status(), target)) {
                throw new ConflictException(
                        "Cannot transition proposal from " + proposal.status() + " to " + target);
            }

            Instant at = clock.instant();
            String decidedBy = SafeLog.id(actor);
            String safeNote = safeNote(note);
            ProposalDecision decision = new ProposalDecision(
                    UUID.randomUUID().toString(), run.runId(), run.storeId(), proposal.productId(),
                    proposal.status(), target, expectedVersion, decidedBy, safeNote,
                    orderReference, at);
            try {
                repository.decideProposal(decision);
            } catch (RuntimeException exception) {
                // Re-read state instead of matching BigQuery ASSERT text. A version/status change
                // means a concurrent decision won; anything else is an upstream failure.
                ReplenishmentProposal current = repository.findProposal(runId, productId)
                        .orElse(null);
                boolean runClosed = repository.find(runId)
                        .map(value -> value.status() != ReplenishmentRunStatus.COMPLETED)
                        .orElse(false);
                if (runClosed || (current != null
                        && (current.version() != expectedVersion
                        || current.status() != proposal.status()))) {
                    throw new ConflictException(
                            "Proposal changed while the decision was being applied; reload and retry");
                }
                throw new UpstreamUnavailableException(
                        "Proposal decision could not be applied; retry", exception);
            }
            return ReplenishmentViewMapper.proposalView(
                    proposal.withDecision(target, expectedVersion + 1, at, decidedBy, safeNote, orderReference));
        } finally {
            runLease.release(runId, leaseOwner);
        }
    }

    private ReplenishmentRun loadRun(String runId) {
        return repository.find(runId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Replenishment run " + runId + " was not found"));
    }

    private ReplenishmentProposal loadProposal(String runId, String productId) {
        return repository.findProposal(runId, productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Proposal " + productId + " in run " + runId + " was not found"));
    }

    private static boolean allowed(ProposalStatus from, ProposalStatus to) {
        return switch (to) {
            case APPROVED -> from == ProposalStatus.PROPOSED;
            case REJECTED -> from == ProposalStatus.PROPOSED || from == ProposalStatus.APPROVED;
            case ORDERED -> from == ProposalStatus.APPROVED;
            case PROPOSED -> false;
        };
    }

    private static String safeNote(String note) {
        if (note == null) {
            return null;
        }
        return Notes.safeNote(note, Notes.MAX_NOTE);
    }
}
