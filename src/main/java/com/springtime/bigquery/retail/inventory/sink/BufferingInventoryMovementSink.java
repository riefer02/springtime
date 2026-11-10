package com.springtime.bigquery.retail.inventory.sink;

import com.springtime.bigquery.config.InventoryBatchProperties;
import com.springtime.bigquery.retail.inventory.application.InventoryMovementBatchSink;
import com.springtime.bigquery.retail.inventory.application.InventoryMovementSink;
import com.springtime.bigquery.retail.inventory.application.InventoryWriteException;
import com.springtime.bigquery.retail.inventory.model.InventoryMovement;
import com.springtime.bigquery.retail.inventory.model.MovementWriteResult;
import com.springtime.bigquery.util.ByteSizes;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Coalesces concurrent single-event writes into one BigQuery batch. Each caller blocks until the
 * batch it joined commits, so acknowledgement semantics stay the same as the single-write path.
 *
 * <p>Shutdown is fail-closed: every accepted caller is completed, normally or exceptionally, before
 * the object is closed.</p>
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "retail.ledger.batch", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class BufferingInventoryMovementSink implements InventoryMovementSink {

    private static final Logger log = LoggerFactory.getLogger(BufferingInventoryMovementSink.class);
    private static final int MAX_FALLBACK_FAILURES = 3;

    private final InventoryMovementBatchSink delegate;
    private final InventoryBatchProperties properties;
    private final MeterRegistry meterRegistry;
    private final BlockingQueue<Pending> queue;
    private final Set<Pending> pending = ConcurrentHashMap.newKeySet();
    private final AtomicLong pendingBytes = new AtomicLong();
    private final Object lifecycle = new Object();
    private volatile boolean running = true;
    private Thread flusher;

    public BufferingInventoryMovementSink(InventoryMovementBatchSink delegate,
                                          InventoryBatchProperties properties,
                                          MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.queue = new LinkedBlockingQueue<>(properties.maxPending());
    }

    @PostConstruct
    void start() {
        Gauge.builder("retail.inventory.movement.batch.pending", queue, BlockingQueue::size)
                .register(meterRegistry);
        flusher = Thread.ofVirtual().name("ledger-batch-flusher").start(this::run);
    }

    @Override
    public MovementWriteResult write(InventoryMovement movement) {
        long bytes = estimateBytes(movement);
        Pending item = new Pending(movement, bytes, new CompletableFuture<>());
        synchronized (lifecycle) {
            if (!running) {
                throw new InventoryWriteException("LEDGER_UNAVAILABLE",
                        "Inventory ledger batch sink is stopping", true, null);
            }
            if (pendingBytes.get() + bytes > properties.maxPendingBytes() || !queue.offer(item)) {
                meterRegistry.counter("retail.inventory.movement.batch.rejected").increment();
                throw new InventoryWriteException("LEDGER_OVERLOAD",
                        "Inventory ledger batch queue is full or over its byte budget", true, null);
            }
            pending.add(item);
            pendingBytes.addAndGet(bytes);
        }
        try {
            return item.result().get(properties.waitTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new InventoryWriteException("LEDGER_TIMEOUT",
                    "Timed out waiting for the inventory ledger batch", true, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new InventoryWriteException("LEDGER_INTERRUPTED",
                    "Interrupted while waiting for the inventory ledger batch", true, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new InventoryWriteException("LEDGER_UNAVAILABLE",
                    "Inventory ledger batch failed", true, cause);
        }
    }

    private void run() {
        while (running) {
            try {
                Pending first = queue.take();
                if (properties.lingerMs() > 0) {
                    Thread.sleep(properties.lingerMs());
                }
                List<Pending> batch = new ArrayList<>(properties.size());
                batch.add(first);
                queue.drainTo(batch, properties.size() - 1);
                flush(batch);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                break;
            } catch (RuntimeException exception) {
                log.error("inventory ledger batch flusher failed", exception);
            }
        }
        flushRemaining();
    }

    private void flush(List<Pending> batch) {
        if (batch.isEmpty()) {
            return;
        }
        List<InventoryMovement> movements = batch.stream().map(Pending::movement).toList();
        try {
            long inserted = delegate.writeBatch(movements);
            MovementWriteResult result = inserted > 0
                    ? MovementWriteResult.INSERTED
                    : MovementWriteResult.DUPLICATE;
            complete(batch, result);
        } catch (InventoryWriteException exception) {
            if (exception.retryable()) {
                fail(batch, exception);
                return;
            }
            fallback(batch, exception);
        } catch (RuntimeException exception) {
            fallback(batch, exception);
        }
    }

    /** A deterministic row rejected the batch; isolate it with a bounded per-row fallback. */
    private void fallback(List<Pending> batch, RuntimeException batchFailure) {
        meterRegistry.counter("retail.inventory.movement.batch.fallback").increment();
        int failures = 0;
        for (Pending item : batch) {
            if (failures >= MAX_FALLBACK_FAILURES) {
                item.result().completeExceptionally(batchFailure);
                release(item);
                continue;
            }
            try {
                item.result().complete(delegate.write(item.movement()));
                failures = 0;
            } catch (RuntimeException rowFailure) {
                failures++;
                item.result().completeExceptionally(rowFailure);
            } finally {
                release(item);
            }
        }
    }

    private void fail(List<Pending> batch, RuntimeException failure) {
        batch.forEach(item -> {
            item.result().completeExceptionally(failure);
            release(item);
        });
    }

    private void complete(List<Pending> batch, MovementWriteResult result) {
        batch.forEach(item -> {
            item.result().complete(result);
            release(item);
        });
    }

    private void release(Pending item) {
        pending.remove(item);
        pendingBytes.addAndGet(-item.bytes());
    }

    private void flushRemaining() {
        List<Pending> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            flush(remaining);
        }
    }

    @PreDestroy
    void stop() {
        synchronized (lifecycle) {
            running = false;
        }
        Thread current = flusher;
        if (current != null) {
            current.interrupt();
            try {
                current.join(5_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        flushRemaining();
        InventoryWriteException stopped = new InventoryWriteException("LEDGER_UNAVAILABLE",
                "Inventory ledger batch sink stopped before this movement committed", true, null);
        for (Pending item : List.copyOf(pending)) {
            item.result().completeExceptionally(stopped);
            release(item);
        }
    }

    private static long estimateBytes(InventoryMovement movement) {
        return 128
                + ByteSizes.estimate(movement.eventId())
                + ByteSizes.estimate(movement.payload());
    }

    private record Pending(InventoryMovement movement, long bytes,
                           CompletableFuture<MovementWriteResult> result) {
    }
}
