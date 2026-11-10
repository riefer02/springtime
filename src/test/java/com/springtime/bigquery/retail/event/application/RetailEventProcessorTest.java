package com.springtime.bigquery.retail.event.application;

import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.retail.event.dto.EventEnvelope;
import com.springtime.bigquery.messaging.RetryExecutor;
import com.springtime.bigquery.messaging.RetryInterruptedException;
import com.springtime.bigquery.messaging.RetryProperties;
import com.springtime.bigquery.retail.event.model.ProcessingResult;
import com.springtime.bigquery.retail.event.model.TransientProcessingException;
import com.springtime.bigquery.retail.inventory.model.InventoryMovement;
import com.springtime.bigquery.retail.inventory.model.InventoryMovementType;
import com.springtime.bigquery.retail.inventory.model.MovementWriteResult;
import com.springtime.bigquery.retail.inventory.application.InventoryMovementSink;
import com.springtime.bigquery.retail.inventory.application.InventoryWriteException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetailEventProcessorTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final EventEnvelope SALE = new EventEnvelope(
            "evt-1", "SALE", Instant.parse("2026-11-09T11:00:00Z"), "1",
            "ST-001", "SKU-0001", 2L, Map.of());

    private final StubSink sink = new StubSink();
    private final RecordingDeadLetterPublisher deadLetters = new RecordingDeadLetterPublisher();
    private final RetailEventTranslator translator = new RetailEventTranslator(properties(), CLOCK);

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    @Test
    void processesSaleAsNegativeInventoryMovement() {
        sink.respond(MovementWriteResult.INSERTED);
        RetailEventProcessor processor = processor(new RetryExecutor(retryProperties(), duration -> {
        }));

        ProcessingResult result = processor.process(SALE);

        assertThat(result).isEqualTo(ProcessingResult.PROCESSED);
        assertThat(sink.calls).isEqualTo(1);
        InventoryMovement movement = sink.movements.get(0);
        assertThat(movement.movementType()).isEqualTo(InventoryMovementType.SALE);
        assertThat(movement.quantityDelta()).isEqualTo(-2L);
        assertThat(movement.businessDate()).isEqualTo(NOW.minusSeconds(3600).atZone(ZoneOffset.UTC).toLocalDate());
        assertThat(deadLetters.calls).isEmpty();
    }

    @Test
    void duplicateEventIsAcknowledgedWithoutDeadLetter() {
        sink.respond(MovementWriteResult.DUPLICATE);
        RetailEventProcessor processor = processor(new RetryExecutor(retryProperties(), duration -> {
        }));

        ProcessingResult result = processor.process(SALE);

        assertThat(result).isEqualTo(ProcessingResult.DUPLICATE);
        assertThat(deadLetters.calls).isEmpty();
    }

    @Test
    void invalidEventGoesStraightToDeadLetterWithoutCallingSink() {
        EventEnvelope invalid = new EventEnvelope(
                "evt-bad", "SALE", NOW.minusSeconds(60), "1",
                "ST-001", null, 2L, Map.of());
        RetailEventProcessor processor = processor(new RetryExecutor(retryProperties(), duration -> {
        }));

        ProcessingResult result = processor.process(invalid);

        assertThat(result).isEqualTo(ProcessingResult.DEAD_LETTERED);
        assertThat(sink.calls).isZero();
        assertThat(deadLetters.calls).singleElement()
                .satisfies(call -> assertThat(call.reason()).isEqualTo("INVALID_EVENT"));
    }

    @Test
    void retryableWriteFailureRetriesThenNacksForBrokerRetry() {
        sink.fail(new InventoryWriteException("LEDGER_UNAVAILABLE", "upstream down", true, null));
        RetailEventProcessor processor = processor(new RetryExecutor(retryProperties(), duration -> {
        }));

        assertThatThrownBy(() -> processor.process(SALE))
                .isInstanceOf(TransientProcessingException.class)
                .hasMessageContaining("unavailable");
        assertThat(sink.calls).isEqualTo(2);
        assertThat(deadLetters.calls).isEmpty();
    }

    @Test
    void nonRetryableWriteFailureGoesToDeadLetterAfterOneAttempt() {
        sink.fail(new InventoryWriteException("LEDGER_REJECTED", "bad schema", false, null));
        RetailEventProcessor processor = processor(new RetryExecutor(retryProperties(), duration -> {
        }));

        ProcessingResult result = processor.process(SALE);

        assertThat(result).isEqualTo(ProcessingResult.DEAD_LETTERED);
        assertThat(sink.calls).isEqualTo(1);
        assertThat(deadLetters.calls).singleElement()
                .satisfies(call -> assertThat(call.reason()).isEqualTo("LEDGER_REJECTED"));
    }

    @Test
    void deadLetterPublishFailureIsTransientSoTheBrokerRedelivers() {
        EventEnvelope invalid = new EventEnvelope(
                "evt-bad", "SALE", NOW.minusSeconds(60), "1",
                "ST-001", null, 2L, Map.of());
        deadLetters.fail = true;
        RetailEventProcessor processor = processor(new RetryExecutor(retryProperties(), duration -> {
        }));

        assertThatThrownBy(() -> processor.process(invalid))
                .isInstanceOf(TransientProcessingException.class)
                .hasMessageContaining("Dead-letter");
    }

    @Test
    void unexpectedFailureIsDeadLetteredInsteadOfLooped() {
        sink.fail(new IllegalStateException("programming bug"));
        RetailEventProcessor processor = processor(new RetryExecutor(retryProperties(), duration -> {
        }));

        assertThat(processor.process(SALE)).isEqualTo(ProcessingResult.DEAD_LETTERED);
        assertThat(sink.calls).isEqualTo(1);
        assertThat(deadLetters.calls).singleElement().satisfies(call -> {
            assertThat(call.reason()).isEqualTo("UNEXPECTED_FAILURE");
            assertThat(call.attempts()).isZero();
        });
    }

    @Test
    void interruptionDuringBackoffIsTransient() {
        sink.fail(new InventoryWriteException("LEDGER_UNAVAILABLE", "upstream down", true, null));
        RetailEventProcessor processor = processor(new RetryExecutor(retryProperties(), duration -> {
            throw new RetryInterruptedException(new InterruptedException("shutdown"));
        }));

        assertThatThrownBy(() -> processor.process(SALE))
                .isInstanceOf(TransientProcessingException.class)
                .hasMessageContaining("Interrupted");
    }

    private RetailEventProcessor processor(RetryExecutor retryExecutor) {
        return new RetailEventProcessor(
                translator,
                sink,
                deadLetters,
                retryExecutor,
                new RetailEventMetrics(new SimpleMeterRegistry()));
    }

    private static RetailProperties properties() {
        return new RetailProperties(
                "retail_demo", 50, 200, 28, 14, 268_435_456L,
                "USD", Duration.ofMinutes(5), Duration.ofDays(365), 0, java.time.ZoneOffset.UTC);
    }

    private static RetryProperties retryProperties() {
        return new RetryProperties(2, Duration.ofMillis(1), 2.0, Duration.ofMillis(1), 0.0);
    }

    private static final class StubSink implements InventoryMovementSink {
        private final List<InventoryMovement> movements = new ArrayList<>();
        private MovementWriteResult result = MovementWriteResult.INSERTED;
        private RuntimeException failure;
        private int calls;

        void respond(MovementWriteResult result) {
            this.result = result;
            this.failure = null;
        }

        void fail(RuntimeException exception) {
            this.failure = exception;
        }

        @Override
        public MovementWriteResult write(InventoryMovement movement) {
            calls++;
            movements.add(movement);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    private static final class RecordingDeadLetterPublisher implements DeadLetterPublisher {
        private final List<DeadLetterCall> calls = new ArrayList<>();
        private boolean fail;

        @Override
        public void publish(EventEnvelope event, String reason, int attempts, Throwable cause) {
            if (fail) {
                throw new IllegalStateException("dead-letter topic unavailable");
            }
            calls.add(new DeadLetterCall(event, reason, attempts));
        }
    }

    private record DeadLetterCall(EventEnvelope event, String reason, int attempts) {
    }
}
