package com.springtime.bigquery.retail.event.consumer;

import com.google.api.gax.batching.FlowControlSettings;
import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.cloud.pubsub.v1.MessageReceiver;
import com.google.cloud.pubsub.v1.Subscriber;
import com.google.pubsub.v1.ProjectSubscriptionName;
import com.google.pubsub.v1.PubsubMessage;
import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.retail.event.dto.EventEnvelope;
import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.retail.event.application.DeadLetterPublisher;
import com.springtime.bigquery.retail.event.application.RetailEventProcessor;
import com.springtime.bigquery.retail.event.model.ProcessingResult;
import com.springtime.bigquery.retail.event.model.TransientProcessingException;
import com.springtime.bigquery.observability.TraceContext;
import com.springtime.bigquery.util.SafeLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Pub/Sub pull subscriber. It acknowledges only after the ledger write commits (or after a
 * dead-letter publish succeeds) and nacks transient failures so Pub/Sub redelivers them.
 */
@Component
@ConditionalOnProperty(name = "messaging.pubsub-enabled", havingValue = "true")
public class PubSubRetailEventSubscriber implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PubSubRetailEventSubscriber.class);
    private static final long STOP_TIMEOUT_SECONDS = 30;
    private static final long START_TIMEOUT_SECONDS = 30;

    private final BigQueryProperties bigQueryProperties;
    private final MessagingProperties messagingProperties;
    private final RetailEventProcessor processor;
    private final DeadLetterPublisher deadLetterPublisher;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final TraceContext traceContext;
    private volatile Subscriber subscriber;

    @Autowired
    public PubSubRetailEventSubscriber(BigQueryProperties bigQueryProperties,
                                       MessagingProperties messagingProperties,
                                       RetailEventProcessor processor,
                                       DeadLetterPublisher deadLetterPublisher,
                                       JsonMapper jsonMapper,
                                       Clock clock,
                                       TraceContext traceContext) {
        this.bigQueryProperties = bigQueryProperties;
        this.messagingProperties = messagingProperties;
        this.processor = processor;
        this.deadLetterPublisher = deadLetterPublisher;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        this.traceContext = traceContext;
    }

    @PostConstruct
    void start() {
        if (!messagingProperties.consumerEnabled()) {
            log.info("Pub/Sub consumer is disabled by configuration");
            return;
        }
        ProjectSubscriptionName subscription = ProjectSubscriptionName.of(
                bigQueryProperties.projectId(), messagingProperties.subscription());
        Subscriber built = Subscriber.newBuilder(subscription, receiver())
                .setParallelPullCount(2)
                .setFlowControlSettings(FlowControlSettings.newBuilder()
                        .setMaxOutstandingElementCount(100L)
                        .setMaxOutstandingRequestBytes(10L * 1024 * 1024)
                        .build())
                .build();
        try {
            built.startAsync().awaitRunning(START_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            built.stopAsync();
            throw new IllegalStateException(
                    "Pub/Sub consumer did not start within " + START_TIMEOUT_SECONDS + "s", exception);
        }
        this.subscriber = built;
        log.info("Pub/Sub consumer started subscription={}", subscription);
    }

    private MessageReceiver receiver() {
        return this::receive;
    }

    void receive(PubsubMessage message, AckReplyConsumer ack) {
        try (TraceContext.SpanScope span = traceContext.startConsumerSpan(
                "retail-event.consume", message.getAttributesMap())) {
            span.setAttribute("messaging.message.id", message.getMessageId());
            EventEnvelope event;
            try {
                event = jsonMapper.readValue(message.getData().toByteArray(), EventEnvelope.class);
            } catch (RuntimeException exception) {
                deadLetterMalformed(message, exception, ack);
                return;
            }

            try {
                ProcessingResult result = processor.process(event);
                ack.ack();
                log.debug("retail-event acked eventId={} result={}",
                        SafeLog.id(event.eventId()), result);
            } catch (TransientProcessingException exception) {
                log.warn("retail-event nacked eventId={} reason={}",
                        SafeLog.id(event.eventId()), exception.getMessage());
                ack.nack();
            } catch (RuntimeException exception) {
                log.error("retail-event nacked eventId={} after unexpected failure",
                        SafeLog.id(event.eventId()), exception);
                ack.nack();
            }
        }
    }

    private void deadLetterMalformed(PubsubMessage message, RuntimeException exception,
                                     AckReplyConsumer ack) {
        try {
            EventEnvelope synthetic = new EventEnvelope(
                    "malformed-" + message.getMessageId(),
                    "UNKNOWN",
                    Instant.now(clock),
                    "unknown",
                    null,
                    null,
                    null,
                    Map.of("rawPayload", rawPayload(message)));
            deadLetterPublisher.publish(synthetic, "MALFORMED_ENVELOPE", 0, exception);
            ack.ack();
            log.error("retail-event payload was not a valid envelope messageId={}; dead-lettered",
                    message.getMessageId());
        } catch (RuntimeException publishFailure) {
            log.error("retail-event malformed payload could not be dead-lettered messageId={}; nacking",
                    message.getMessageId());
            ack.nack();
        }
    }

    private static String rawPayload(PubsubMessage message) {
        String raw = message.getData().toString(StandardCharsets.UTF_8);
        return raw.length() <= 4_096 ? raw : raw.substring(0, 4_096);
    }

    @Override
    @PreDestroy
    public void close() {
        Subscriber current = subscriber;
        if (current == null) {
            return;
        }
        current.stopAsync();
        try {
            current.awaitTerminated(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException exception) {
            log.warn("Pub/Sub consumer did not stop within {}s", STOP_TIMEOUT_SECONDS);
        } catch (RuntimeException exception) {
            log.warn("Pub/Sub consumer did not stop cleanly", exception);
        }
        subscriber = null;
    }
}
