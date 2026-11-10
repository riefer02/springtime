package com.springtime.bigquery.retail.replenishment.application;

import com.springtime.bigquery.control.run.RunLease;
import com.springtime.bigquery.error.ConflictException;
import com.springtime.bigquery.error.UpstreamUnavailableException;
import com.springtime.bigquery.retail.replenishment.model.ProposalDecision;
import com.springtime.bigquery.retail.replenishment.model.ProposalStatus;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentProposal;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRun;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRunStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProposalDecisionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final LocalDate LATEST = LocalDate.of(2026, 11, 9);
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ReplenishmentRunRepository repository = mock(ReplenishmentRunRepository.class);
    private final RunLease runLease = mock(RunLease.class);
    private final ProposalDecisionService service =
            new ProposalDecisionService(repository, runLease, CLOCK);

    @org.junit.jupiter.api.BeforeEach
    void leaseIsAvailable() {
        when(runLease.tryAcquire(anyString(), anyString(), any())).thenReturn(true);
    }

    @Test
    void approvesAProposedItemAndWritesTheAuditRecord() {
        when(repository.find("run-1")).thenReturn(Optional.of(run()));
        when(repository.findProposal("run-1", "SKU-0001"))
                .thenReturn(Optional.of(proposal(ProposalStatus.PROPOSED, 0)));
        var view = service.approve("run-1", "SKU-0001", 0, "ok", "planner");

        assertThat(view.status()).isEqualTo("APPROVED");
        assertThat(view.version()).isEqualTo(1);
        assertThat(view.decidedBy()).isEqualTo("planner");
        ArgumentCaptor<ProposalDecision> decision = ArgumentCaptor.forClass(ProposalDecision.class);
        verify(repository).decideProposal(decision.capture());
        verify(runLease).release(eq("run-1"), anyString());
        assertThat(decision.getValue().fromStatus()).isEqualTo(ProposalStatus.PROPOSED);
        assertThat(decision.getValue().toStatus()).isEqualTo(ProposalStatus.APPROVED);
        assertThat(decision.getValue().expectedVersion()).isZero();
    }

    @Test
    void rejectsAnApprovedItem() {
        when(repository.find("run-1")).thenReturn(Optional.of(run()));
        when(repository.findProposal("run-1", "SKU-0001"))
                .thenReturn(Optional.of(proposal(ProposalStatus.APPROVED, 1)));
        var view = service.reject("run-1", "SKU-0001", 1, "wrong item", "planner");

        assertThat(view.status()).isEqualTo("REJECTED");
        assertThat(view.version()).isEqualTo(2);
    }

    @Test
    void marksAnApprovedItemOrderedWithAReference() {
        when(repository.find("run-1")).thenReturn(Optional.of(run()));
        when(repository.findProposal("run-1", "SKU-0001"))
                .thenReturn(Optional.of(proposal(ProposalStatus.APPROVED, 1)));
        var view = service.order("run-1", "SKU-0001", 1, "order it", "PO-123", "planner");

        assertThat(view.status()).isEqualTo("ORDERED");
        assertThat(view.orderReference()).isEqualTo("PO-123");
        assertThat(view.version()).isEqualTo(2);
    }

    @Test
    void refusesADecisionWhenTheRunIsBeingReplaced() {
        when(runLease.tryAcquire(anyString(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.approve("run-1", "SKU-0001", 0, null, "planner"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("being replaced");
        verify(repository, never()).decideProposal(any());
    }

    @Test
    void refusesADecisionOnANonCompletedRun() {
        ReplenishmentRun failedRun = new ReplenishmentRun(
                "run-1", "ST-001", LATEST, ReplenishmentRunStatus.FAILED,
                28, 14, "planner", NOW, NOW, NOW, 0, BigDecimal.ZERO, "boom");
        when(repository.find("run-1")).thenReturn(Optional.of(failedRun));

        assertThatThrownBy(() -> service.approve("run-1", "SKU-0001", 0, null, "planner"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("not completed");
        verify(repository, never()).decideProposal(any());
    }

    @Test
    void orderRequiresAReference() {
        assertThatThrownBy(() -> service.order("run-1", "SKU-0001", 1, null, " ", "planner"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("orderReference");
    }

    @Test
    void rejectsAStaleVersion() {
        when(repository.find("run-1")).thenReturn(Optional.of(run()));
        when(repository.findProposal("run-1", "SKU-0001"))
                .thenReturn(Optional.of(proposal(ProposalStatus.PROPOSED, 3)));

        assertThatThrownBy(() -> service.approve("run-1", "SKU-0001", 2, null, "planner"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsAnInvalidTransition() {
        when(repository.find("run-1")).thenReturn(Optional.of(run()));
        when(repository.findProposal("run-1", "SKU-0001"))
                .thenReturn(Optional.of(proposal(ProposalStatus.PROPOSED, 0)));

        assertThatThrownBy(() -> service.order("run-1", "SKU-0001", 0, null, "PO-1", "planner"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot transition");

        verify(repository, never()).decideProposal(any());
    }

    @Test
    void approvingAnAlreadyApprovedItemIsIdempotent() {
        when(repository.find("run-1")).thenReturn(Optional.of(run()));
        when(repository.findProposal("run-1", "SKU-0001"))
                .thenReturn(Optional.of(proposal(ProposalStatus.APPROVED, 1)));

        var view = service.approve("run-1", "SKU-0001", 1, null, "planner");

        assertThat(view.status()).isEqualTo("APPROVED");
        verify(repository, never()).decideProposal(any());
    }

    @Test
    void decisionFailureIsReportedAsUnavailable() {
        when(repository.find("run-1")).thenReturn(Optional.of(run()));
        when(repository.findProposal("run-1", "SKU-0001"))
                .thenReturn(Optional.of(proposal(ProposalStatus.PROPOSED, 0)));
        doThrow(new IllegalStateException("audit down")).when(repository).decideProposal(any());

        assertThatThrownBy(() -> service.approve("run-1", "SKU-0001", 0, null, "planner"))
                .isInstanceOf(UpstreamUnavailableException.class)
                .hasMessageContaining("could not be applied");
    }

    @Test
    void optimisticRaceInTheTransactionIsAConflict() {
        when(repository.find("run-1")).thenReturn(Optional.of(run()));
        when(repository.findProposal("run-1", "SKU-0001"))
                .thenReturn(Optional.of(proposal(ProposalStatus.PROPOSED, 0)));
        when(repository.findProposal("run-1", "SKU-0001"))
                .thenReturn(Optional.of(proposal(ProposalStatus.PROPOSED, 0)),
                        Optional.of(proposal(ProposalStatus.APPROVED, 1)));
        when(repository.find("run-1")).thenReturn(Optional.of(run()));
        doThrow(new IllegalStateException("transaction failed"))
                .when(repository).decideProposal(any());

        assertThatThrownBy(() -> service.approve("run-1", "SKU-0001", 0, null, "planner"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("changed");
    }

    @Test
    void listsDecisionHistory() {
        when(repository.find("run-1")).thenReturn(Optional.of(run()));
        when(repository.findProposal("run-1", "SKU-0001"))
                .thenReturn(Optional.of(proposal(ProposalStatus.APPROVED, 1)));
        when(repository.decisions("run-1", "SKU-0001", 51, 0)).thenReturn(List.of(
                new ProposalDecision("d-1", "run-1", "ST-001", "SKU-0001",
                        ProposalStatus.PROPOSED, ProposalStatus.APPROVED, 0, "planner",
                        "ok", null, NOW)));

        var history = service.history("run-1", "SKU-0001", null, 50);

        assertThat(history.nextPageToken()).isNull();
        assertThat(history.items()).singleElement().satisfies(view -> {
            assertThat(view.fromStatus()).isEqualTo("PROPOSED");
            assertThat(view.toStatus()).isEqualTo("APPROVED");
            assertThat(view.decidedBy()).isEqualTo("planner");
        });
    }

    private static ReplenishmentRun run() {
        return new ReplenishmentRun(
                "run-1", "ST-001", LATEST, ReplenishmentRunStatus.COMPLETED,
                28, 14, "planner", NOW, NOW, NOW, 1, new BigDecimal("102.00"), null);
    }

    private static ReplenishmentProposal proposal(ProposalStatus status, int version) {
        return new ReplenishmentProposal(
                "run-1", LATEST, "ST-001", "SKU-0001", "SKU-0001", "Coffee 1",
                "Beverages", "SUP-001", "Supplier 1", 52, 4.2, 12.3, 5,
                60, 24, new BigDecimal("102.00"), "BELOW_REORDER_POINT",
                status, version, null, null, null, null);
    }
}
