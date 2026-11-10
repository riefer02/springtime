package com.springtime.bigquery.messaging;

import com.springtime.bigquery.observability.TraceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * Local, bounded broker adapter for tests and same-process demos. It is deliberately not durable:
 * use Pub/Sub in any shared or production environment.
 *
 * <p>It fails closed at capacity instead of evicting messages. A full queue is an operational error,
 * not a reason to lose an event that the API already accepted with {@code 202}.</p>
 */
@Component
@ConditionalOnProperty(name = "messaging.pubsub-enabled", havingValue = "false", matchIfMissing = true)
public class InMemoryMessagePublisher implements MessagePublisher {

    private final BlockingQueue<PublishedMessage> events;
    private final BlockingQueue<PublishedMessage> deadLetters;
    private final String deadLetterTopic;
    private final TraceContext traceContext;

    public InMemoryMessagePublisher(MessagingProperties properties) {
        this(properties, TraceContext.noop());
    }

    @Autowired
    public InMemoryMessagePublisher(MessagingProperties properties, TraceContext traceContext) {
        int capacity = properties.maxInMemoryMessages();
        this.events = new ArrayBlockingQueue<>(capacity);
        this.deadLetters = new ArrayBlockingQueue<>(capacity);
        this.deadLetterTopic = properties.deadLetterTopic();
        this.traceContext = traceContext;
    }

    @Override
    public String publish(String topic, MessagePayload payload) {
        return publish(topic, payload, Map.of());
    }

    @Override
    public String publish(String topic, MessagePayload payload, Map<String, String> attributes) {
        PublishedMessage message = new PublishedMessage(
                topic, payload, "local-" + UUID.randomUUID(),
                traceContext.withCurrentTrace(attributes));
        BlockingQueue<PublishedMessage> target = deadLetterTopic.equals(topic) ? deadLetters : events;
        if (!target.offer(message)) {
            throw new MessagingException("Local broker is full for topic " + topic
                    + "; refusing to drop an accepted event");
        }
        return message.messageId();
    }

    /** Poll one normal event for a local consumer. Dead letters are never delivered as events. */
    public Optional<PublishedMessage> poll() {
        return Optional.ofNullable(events.poll());
    }

    /** Return an event to the queue after a transient processing failure. */
    public void requeue(PublishedMessage message) {
        if (!events.offer(message)) {
            throw new MessagingException("Local broker is full while requeueing " + message.messageId());
        }
    }

    /** Snapshot of normal events, oldest first. */
    public List<PublishedMessage> publishedEvents() {
        return List.copyOf(events);
    }

    /** Snapshot of dead-lettered events, oldest first. */
    public List<PublishedMessage> deadLetters() {
        return List.copyOf(deadLetters);
    }

    public void clear() {
        events.clear();
        deadLetters.clear();
    }

    public record PublishedMessage(String topic, MessagePayload payload, String messageId,
                                   Map<String, String> attributes) {

        public PublishedMessage {
            attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        }
    }
}
