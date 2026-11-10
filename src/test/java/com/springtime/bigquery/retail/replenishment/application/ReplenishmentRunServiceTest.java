package com.springtime.bigquery.retail.replenishment.application;

import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.control.run.RunLease;
import com.springtime.bigquery.error.ConflictException;
import com.springtime.bigquery.error.InvalidQueryException;
import com.springtime.bigquery.error.ResourceNotFoundException;
import com.springtime.bigquery.retail.query.RetailQueryPort;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentRunRequest;
import com.springtime.bigquery.retail.replenishment.dto.ReplenishmentRunView;
import com.springtime.bigquery.retail.replenishment.model.ProposalStatus;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentProposal;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRun;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRunStatus;
import com.springtime.bigquery.retail.replenishment.model.ReplenishmentRunSummary;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReplenishmentRunServiceTest {

    private static final LocalDate LATEST = LocalDate.of(2026, 11, 9);
    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ReplenishmentRunRepository repository = mock(ReplenishmentRunRepository.class);
    private final RetailQueryPort retailQueryPort = mock(RetailQueryPort.class);
    private final RunLease runLease = mock(RunLease.class);
    private final ReplenishmentRunService service = new ReplenishmentRunService(
            repository, retailQueryPort, properties(),
            new ReplenishmentMetrics(new SimpleMeterRegistry()), runLease, CLOCK);

    @org.junit.jupiter.api.BeforeEach
    void leaseIsAvailable() {
        org.mockito.Mockito.lenient().when(retailQueryPort.storeExists(anyString())).thenReturn(true);
        when(runLease.tryAcquire(anyString(), anyString(), any())).thenReturn(true);
    }

    @Test
    void rejectsAnUnknownStoreBeforeTouchingTheRun() {
        when(retailQueryPort.storeExists("ST-404")).thenReturn(false);

        ReplenishmentRunRequest unknownStore =
                new ReplenishmentRunRequest("run-1", "ST-404", null, null, null);
        assertThatThrownBy(() -> service.create(unknownStore, "tester"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("was not found");
        verify(repository, never()).begin(any());
        verify(runLease, never()).tryAcquire(anyString(), anyString(), any());
    }

    @Test
    void refusesWhenAnotherOwnerHoldsTheRunLease() {
        when(retailQueryPort.latestSnapshotDate()).thenReturn(LATEST);
        when(runLease.tryAcquire(anyString(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.create(request("run-1", null, null), "tester"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already being created");
        verify(repository, never()).begin(any());
        verify(runLease, never()).release(anyString(), anyString());
    }

    @Test
    void createsACompletedRunAndReturnsItsSummary() {
        when(retailQueryPort.latestSnapshotDate()).thenReturn(LATEST);
        when(repository.summary("run-1")).thenReturn(
                new ReplenishmentRunSummary(2, new BigDecimal("10.50")));

        ReplenishmentRunView view = service.create(request("run-1", null, null), "tester");

        assertThat(view.runId()).isEqualTo("run-1");
        assertThat(view.status()).isEqualTo("COMPLETED");
        assertThat(view.asOfDate()).isEqualTo(LATEST);
        assertThat(view.proposalCount()).isEqualTo(2);
        assertThat(view.totalOrderValue()).isEqualTo("10.50");
        assertThat(view.requestedBy()).isEqualTo("tester");

        InOrder order = inOrder(repository);
        ArgumentCaptor<ReplenishmentRun> run = ArgumentCaptor.forClass(ReplenishmentRun.class);
        order.verify(repository).begin(run.capture());
        order.verify(repository).clearProposals("run-1");
        order.verify(repository).mergeProposals(run.getValue(), 5_000);
        order.verify(repository).summary("run-1");
        order.verify(repository).complete(anyString(), any(), any());
        verify(runLease).release(eq("run-1"), anyString());
    }

    @Test
    void generatesARunIdAndUsesConfiguredDefaults() {
        when(retailQueryPort.latestSnapshotDate()).thenReturn(LATEST);
        when(repository.summary(anyString())).thenReturn(ReplenishmentRunSummary.empty());

        ReplenishmentRunView view = service.create(request(null, null, null), "tester");

        assertThat(view.runId()).isNotBlank();
        assertThat(view.demandWindowDays()).isEqualTo(28);
        assertThat(view.targetCoverDays()).isEqualTo(14);
    }

    @Test
    void marksTheRunFailedWhenTheMergeFails() {
        when(retailQueryPort.latestSnapshotDate()).thenReturn(LATEST);
        doThrow(new IllegalStateException("merge failed"))
                .when(repository).mergeProposals(any(), anyInt());

        assertThatThrownBy(() -> service.create(request("run-2", null, null), "tester"))
                .isInstanceOf(IllegalStateException.class);

        verify(repository).fail(anyString(), anyString(), any());
        verify(runLease).release(eq("run-2"), anyString());
    }

    @Test
    void rejectsAReRunWithDecisionsWithoutFailingTheCompletedRun() {
        when(retailQueryPort.latestSnapshotDate()).thenReturn(LATEST);
        when(repository.hasDecisions("run-1")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request("run-1", null, null), "tester"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already has decisions");

        verify(repository, never()).begin(any());
        verify(repository, never()).fail(anyString(), anyString(), any());
        verify(runLease).release(eq("run-1"), anyString());
    }

    @Test
    void doesNotMarkTheRunFailedWhenBeginItselfFails() {
        when(retailQueryPort.latestSnapshotDate()).thenReturn(LATEST);
        doThrow(new IllegalStateException("begin failed")).when(repository).begin(any());

        assertThatThrownBy(() -> service.create(request("run-1", null, null), "tester"))
                .isInstanceOf(IllegalStateException.class);

        verify(repository, never()).fail(anyString(), anyString(), any());
        verify(runLease).release(eq("run-1"), anyString());
    }

    @Test
    void rejectsFutureAsOfDate() {
        assertThatThrownBy(() -> service.create(
                request("run-1", LATEST.plusDays(1), null), "tester"))
                .isInstanceOf(InvalidQueryException.class)
                .hasMessageContaining("future");
    }

    @Test
    void returnsNotFoundForUnknownRun() {
        when(repository.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("missing"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void proposalsRequireTheRunToExist() {
        when(repository.find("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.proposals("missing", null, 50))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void listsAndMapsProposals() {
        when(repository.find("run-1")).thenReturn(Optional.of(run("run-1")));
        when(repository.proposals("run-1", 51, 0)).thenReturn(List.of(proposal()));

        var proposals = service.proposals("run-1", null, 50);

        assertThat(proposals.pageSize()).isEqualTo(50);
        assertThat(proposals.nextPageToken()).isNull();
        assertThat(proposals.items()).singleElement().satisfies(view -> {
            assertThat(view.productId()).isEqualTo("SKU-0001");
            assertThat(view.recommendedUnits()).isEqualTo(24);
            assertThat(view.orderValue()).isEqualTo("102.00");
            assertThat(view.reason()).isEqualTo("BELOW_REORDER_POINT");
        });
    }

    @Test
    void pagesProposalsAndRejectsMalformedTokens() {
        when(repository.find("run-1")).thenReturn(Optional.of(run("run-1")));
        when(repository.proposals("run-1", 2, 0))
                .thenReturn(List.of(proposal(), proposal()));

        var page = service.proposals("run-1", null, 1);

        assertThat(page.items()).hasSize(1);
        assertThat(page.nextPageToken()).isNotNull();

        assertThatThrownBy(() -> service.proposals("run-1", "not-a-token", 50))
                .isInstanceOf(com.springtime.bigquery.error.InvalidRequestException.class);
    }

    private static ReplenishmentRunRequest request(String runId, LocalDate asOf, Integer target) {
        return new ReplenishmentRunRequest(runId, "ST-001", asOf, null, target);
    }

    private static ReplenishmentRun run(String runId) {
        return new ReplenishmentRun(
                runId, "ST-001", LATEST, ReplenishmentRunStatus.COMPLETED,
                28, 14, "tester", NOW, NOW, NOW, 1, new BigDecimal("102.00"), null);
    }

    private static ReplenishmentProposal proposal() {
        return new ReplenishmentProposal(
                "run-1", LATEST, "ST-001", "SKU-0001", "SKU-0001", "Coffee 1",
                "Beverages", "SUP-001", "Supplier 1", 52, 4.2, 12.3, 5,
                60, 24, new BigDecimal("102.00"), "BELOW_REORDER_POINT",
                ProposalStatus.PROPOSED, 0, null, null, null, null);
    }

    private static RetailProperties properties() {
        return new RetailProperties(
                "retail_demo", 50, 200, 28, 14, 268_435_456L,
                "USD", Duration.ofMinutes(5), Duration.ofDays(365), 0, java.time.ZoneOffset.UTC);
    }
}
