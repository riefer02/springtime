package com.springtime.bigquery.retail.event.application;

import com.springtime.bigquery.retail.event.dto.EventEnvelope;
import com.springtime.bigquery.messaging.RetryExhaustedException;
import com.springtime.bigquery.messaging.RetryExecutor;
import com.springtime.bigquery.messaging.RetryInterruptedException;
import com.springtime.bigquery.retail.event.model.PermanentEventException;
import com.springtime.bigquery.retail.event.model.ProcessingResult;
import com.springtime.bigquery.retail.event.model.TransientProcessingException;
import com.springtime.bigquery.retail.inventory.model.InventoryMovement;
import com.springtime.bigquery.retail.inventory.model.MovementWriteResult;
import com.springtime.bigquery.retail.inventory.application.InventoryMovementSink;
import com.springtime.bigquery.retail.inventory.application.InventoryWriteException;
import com.springtime.bigquery.util.SafeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Consumer-side orchestration: validate, retry only transient ledger failures, and dead-letter the
 * rest. Returning {@link ProcessingResult#DEAD_LETTERED} means the dead-letter publish succeeded,
 * so the original message can be acknowledged.
 */
@Component
public class RetailEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(RetailEventProcessor.class);

    private final RetailEventTranslator translator;
    private final InventoryMovementSink sink;
    private final DeadLetterPublisher deadLetterPublisher;
    private final RetryExecutor retryExecutor;
    private final RetailEventMetrics metrics;

    public RetailEventProcessor(RetailEventTranslator translator,
                                InventoryMovementSink sink,
                                DeadLetterPublisher deadLetterPublisher,
                                RetryExecutor retryExecutor,
                                RetailEventMetrics metrics) {
        this.translator = translator;
        this.sink = sink;
        this.deadLetterPublisher = deadLetterPublisher;
        this.retryExecutor = retryExecutor;
        this.metrics = metrics;
    }

    public ProcessingResult process(EventEnvelope event) {
        InventoryMovement movement;
        try {
            movement = translator.translate(event);
        } catch (PermanentEventException exception) {
            log.warn("retail-event rejected eventId={} reason={} detail={}",
                    SafeLog.id(event.eventId()), exception.reason(), exception.getMessage());
            return deadLetter(event, exception.reason(), 0, exception);
        }

        try {
            MovementWriteResult result = retryExecutor.execute(
                    "inventory-movement:" + SafeLog.id(event.eventId()),
                    () -> sink.write(movement),
                    RetailEventProcessor::isRetryable);
            metrics.recordProcessed(event.eventType(), result);
            return result == MovementWriteResult.INSERTED
                    ? ProcessingResult.PROCESSED
                    : ProcessingResult.DUPLICATE;
        } catch (InventoryWriteException exception) {
            metrics.recordSinkFailure(event.eventType(), exception.retryable());
            return deadLetter(event, exception.reason(), 1, exception);
        } catch (RetryExhaustedException exception) {
            metrics.recordRetryExhausted(event.eventType());
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            if (cause instanceof InventoryWriteException writeException
                    && writeException.retryable()) {
                // The sink did not become available within the in-process budget. Nack so Pub/Sub
                // can redeliver with its longer retry policy instead of forcing an operator replay.
                throw new TransientProcessingException(
                        "Inventory ledger unavailable for event " + SafeLog.id(event.eventId()),
                        exception);
            }
            log.error("retail-event exhausted retries eventId={} attempts={}",
                    SafeLog.id(event.eventId()), exception.attempts());
            return deadLetter(event, "RETRY_EXHAUSTED", exception.attempts(), cause);
        } catch (RetryInterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new TransientProcessingException(
                    "Interrupted while processing event " + SafeLog.id(event.eventId()), exception);
        } catch (RuntimeException exception) {
            // A translation/sink bug is not a transient transport failure. Dead-letter it instead
            // of looping the same poison message through every replica.
            log.error("retail-event failed unexpectedly eventId={}",
                    SafeLog.id(event.eventId()), exception);
            return deadLetter(event, "UNEXPECTED_FAILURE", 0, exception);
        }
    }

    private static boolean isRetryable(RuntimeException exception) {
        return exception instanceof InventoryWriteException writeException
                && writeException.retryable();
    }

    private ProcessingResult deadLetter(EventEnvelope event, String reason, int attempts, Throwable cause) {
        try {
            deadLetterPublisher.publish(event, reason, attempts, cause);
            metrics.recordDeadLettered(event.eventType(), reason);
            return ProcessingResult.DEAD_LETTERED;
        } catch (RuntimeException exception) {
            throw new TransientProcessingException(
                    "Dead-letter publish failed for event " + SafeLog.id(event.eventId()), exception);
        }
    }
}
