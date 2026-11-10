package com.springtime.bigquery.retail.inventory.sink;

import com.springtime.bigquery.config.InventoryBatchProperties;
import com.springtime.bigquery.retail.inventory.application.InventoryMovementBatchSink;
import com.springtime.bigquery.retail.inventory.application.InventoryWriteException;
import com.springtime.bigquery.retail.inventory.model.InventoryMovement;
import com.springtime.bigquery.retail.inventory.model.InventoryMovementType;
import com.springtime.bigquery.retail.inventory.model.MovementWriteResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BufferingInventoryMovementSinkTest {

    private static final InventoryMovement MOVEMENT = new InventoryMovement(
            "evt-1", InventoryMovementType.SALE, Instant.parse("2026-11-09T12:00:00Z"),
            LocalDate.of(2026, 11, 9), "ST-001", "SKU-0001", -2L,
            new BigDecimal("1.25"), "USD", "retail-api", "POS-1", "1", Map.of());

    private final InventoryMovementBatchSink delegate = mock(InventoryMovementBatchSink.class);
    private final BufferingInventoryMovementSink sink = new BufferingInventoryMovementSink(
            delegate, new InventoryBatchProperties(true, 10, 50, 100, 33_554_432, Duration.ofSeconds(5)),
            new SimpleMeterRegistry());

    @Test
    void coalescesConcurrentWritesIntoOneBatch() throws Exception {
        when(delegate.writeBatch(anyList())).thenReturn(2L);
        sink.start();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<MovementWriteResult> first =
                    CompletableFuture.supplyAsync(() -> sink.write(MOVEMENT), executor);
            CompletableFuture<MovementWriteResult> second =
                    CompletableFuture.supplyAsync(() -> sink.write(MOVEMENT), executor);

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(MovementWriteResult.INSERTED);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(MovementWriteResult.INSERTED);
            verify(delegate, times(1)).writeBatch(anyList());
        } finally {
            executor.shutdownNow();
            sink.stop();
        }
    }

    @Test
    void fiftyConcurrentWritesCoalesceWithoutLossOrRejection() throws Exception {
        java.util.concurrent.atomic.AtomicInteger batched =
                new java.util.concurrent.atomic.AtomicInteger();
        when(delegate.writeBatch(anyList())).thenAnswer(invocation -> {
            List<com.springtime.bigquery.retail.inventory.model.InventoryMovement> ignored = invocation.getArgument(0);
            int size = ignored.size();
            batched.addAndGet(size);
            return (long) size;
        });
        BufferingInventoryMovementSink capacity = new BufferingInventoryMovementSink(
                delegate, new InventoryBatchProperties(true, 10, 10, 200, 33_554_432,
                        Duration.ofSeconds(5)),
                new SimpleMeterRegistry());
        capacity.start();
        ExecutorService executor = Executors.newFixedThreadPool(16);
        try {
            List<CompletableFuture<MovementWriteResult>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 50; i++) {
                int index = i;
                futures.add(CompletableFuture.supplyAsync(() -> capacity.write(new InventoryMovement(
                        "evt-" + index, InventoryMovementType.SALE,
                        Instant.parse("2026-11-09T12:00:00Z"), LocalDate.of(2026, 11, 9),
                        "ST-001", "SKU-0001", -1L, null, "USD",
                        "retail-api", "POS-" + index, "1", Map.of())), executor));
            }
            for (CompletableFuture<MovementWriteResult> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(MovementWriteResult.INSERTED);
            }
            assertThat(batched.get()).isEqualTo(50);
        } finally {
            executor.shutdownNow();
            capacity.stop();
        }
    }

    @Test
    void rejectsWhenThePendingByteBudgetIsExhausted() throws Exception {
        InventoryMovement large = new InventoryMovement(
                "evt-large", InventoryMovementType.SALE, Instant.parse("2026-11-09T12:00:00Z"),
                LocalDate.of(2026, 11, 9), "ST-001", "SKU-0001", -2L,
                new BigDecimal("1.25"), "USD", "retail-api", "POS-1", "1",
                Map.of("blob", "x".repeat(4096)));
        BufferingInventoryMovementSink small = new BufferingInventoryMovementSink(
                delegate, new InventoryBatchProperties(true, 10, 50, 100, 8_000, Duration.ofSeconds(5)),
                new SimpleMeterRegistry());
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        when(delegate.writeBatch(anyList())).thenAnswer(invocation -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return 1L;
        });
        small.start();
        try {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                CompletableFuture<MovementWriteResult> held =
                        CompletableFuture.supplyAsync(() -> small.write(large), executor);
                // Wait until the first item is inside the batch (bytes counted) before the second.
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> small.write(large))
                        .isInstanceOf(InventoryWriteException.class)
                        .hasMessageContaining("over its byte budget");
                release.countDown();
                assertThat(held.get(5, TimeUnit.SECONDS)).isEqualTo(MovementWriteResult.INSERTED);
            } finally {
                release.countDown();
                executor.shutdownNow();
            }
        } finally {
            small.stop();
        }
    }

    @Test
    void mixedBatchOutcomeIsBatchLevelAndDocumented() throws Exception {
        when(delegate.writeBatch(anyList())).thenReturn(1L);
        sink.start();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<MovementWriteResult> first =
                    CompletableFuture.supplyAsync(() -> sink.write(MOVEMENT), executor);
            CompletableFuture<MovementWriteResult> second =
                    CompletableFuture.supplyAsync(() -> sink.write(MOVEMENT), executor);

            // One affected row out of two: both callers see INSERTED. That is the documented
            // batch-level contract, not per-event truth.
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(MovementWriteResult.INSERTED);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(MovementWriteResult.INSERTED);
        } finally {
            executor.shutdownNow();
            sink.stop();
        }
    }

    @Test
    void writesAfterStopFailFast() {
        sink.start();
        sink.stop();

        assertThatThrownBy(() -> sink.write(MOVEMENT))
                .isInstanceOf(InventoryWriteException.class)
                .hasMessageContaining("stopping");
    }

    @Test
    void retryableBatchFailureDoesNotFanOutIntoPerRowWrites() throws Exception {
        when(delegate.writeBatch(anyList())).thenThrow(
                new InventoryWriteException("LEDGER_UNAVAILABLE", "upstream down", true, null));
        sink.start();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<MovementWriteResult> first =
                    CompletableFuture.supplyAsync(() -> sink.write(MOVEMENT), executor);
            CompletableFuture<MovementWriteResult> second =
                    CompletableFuture.supplyAsync(() -> sink.write(MOVEMENT), executor);

            assertThatThrownBy(() -> first.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InventoryWriteException.class);
            assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(InventoryWriteException.class);
            verify(delegate, never()).write(any());
        } finally {
            executor.shutdownNow();
            sink.stop();
        }
    }

    @Test
    void fallsBackPerRowWhenTheBatchFails() throws Exception {
        when(delegate.writeBatch(anyList())).thenThrow(new IllegalStateException("batch down"));
        when(delegate.write(any())).thenReturn(MovementWriteResult.INSERTED);
        sink.start();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<MovementWriteResult> first =
                    CompletableFuture.supplyAsync(() -> sink.write(MOVEMENT), executor);
            CompletableFuture<MovementWriteResult> second =
                    CompletableFuture.supplyAsync(() -> sink.write(MOVEMENT), executor);

            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(MovementWriteResult.INSERTED);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(MovementWriteResult.INSERTED);
            verify(delegate, times(2)).write(any());
        } finally {
            executor.shutdownNow();
            sink.stop();
        }
    }
}
