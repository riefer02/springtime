package com.springtime.bigquery.retail.event.consumer;

import com.springtime.bigquery.retail.event.dto.EventEnvelope;
import com.springtime.bigquery.messaging.InMemoryMessagePublisher;
import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.retail.event.application.RetailEventProcessor;
import com.springtime.bigquery.observability.TraceContext;
import com.springtime.bigquery.util.SafeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Local-only relay that turns the bounded in-memory publisher into an end-to-end demo. It is not a
 * production broker; Pub/Sub uses {@link PubSubRetailEventSubscriber} instead.
 */
@Component
@ConditionalOnProperty(name = "messaging.pubsub-enabled", havingValue = "false", matchIfMissing = true)
public class LocalRetailEventRelay {

    private static final Logger log = LoggerFactory.getLogger(LocalRetailEventRelay.class);
    private static final int MAX_MESSAGES_PER_TICK = 100;

    private final InMemoryMessagePublisher publisher;
    private final RetailEventProcessor processor;
    private final MessagingProperties properties;
    private final TraceContext traceContext;

    public LocalRetailEventRelay(InMemoryMessagePublisher publisher,
                                 RetailEventProcessor processor,
                                 MessagingProperties properties) {
        this(publisher, processor, properties, TraceContext.noop());
    }

    @Autowired
    public LocalRetailEventRelay(InMemoryMessagePublisher publisher,
                                 RetailEventProcessor processor,
                                 MessagingProperties properties,
                                 TraceContext traceContext) {
        this.publisher = publisher;
        this.processor = processor;
        this.properties = properties;
        this.traceContext = traceContext;
    }

    @Scheduled(fixedDelayString = "${messaging.local-consumer-poll-interval-ms:500}")
    public void drain() {
        if (!properties.localConsumerEnabled() || !properties.consumerEnabled()) {
            return;
        }
        for (int i = 0; i < MAX_MESSAGES_PER_TICK; i++) {
            InMemoryMessagePublisher.PublishedMessage message = publisher.poll().orElse(null);
            if (message == null) {
                return;
            }
            if (!(message.payload() instanceof EventEnvelope event)) {
                log.warn("Local relay ignored non-event payload messageId={}", message.messageId());
                continue;
            }
            try {
                try (TraceContext.SpanScope span = traceContext.startConsumerSpan(
                        "retail-event.consume", message.attributes())) {
                    span.setAttribute("messaging.message.id", message.messageId());
                    processor.process(event);
                }
            } catch (RuntimeException exception) {
                log.warn("Local relay requeueing eventId={} after transient failure: {}",
                        SafeLog.id(event.eventId()), exception.getMessage());
                try {
                    publisher.requeue(message);
                } catch (RuntimeException requeueFailure) {
                    log.error("Local relay could not requeue eventId={}",
                            SafeLog.id(event.eventId()), requeueFailure);
                }
                return;
            }
        }
    }
}
