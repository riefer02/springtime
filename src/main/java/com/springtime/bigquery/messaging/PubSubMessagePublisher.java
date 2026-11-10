package com.springtime.bigquery.messaging;

import com.google.cloud.pubsub.v1.Publisher;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.PubsubMessage;
import com.google.pubsub.v1.TopicName;
import com.springtime.bigquery.observability.TraceContext;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Production publisher. Topic, subscription, and dead-letter policy are infrastructure concerns;
 * this adapter only writes messages. It is lazy so the application can start without credentials
 * when Pub/Sub is disabled.
 */
@Component
@ConditionalOnProperty(name = "messaging.pubsub-enabled", havingValue = "true")
public class PubSubMessagePublisher implements MessagePublisher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PubSubMessagePublisher.class);
    private static final long PUBLISH_TIMEOUT_SECONDS = 10;
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 30;

    private final String projectId;
    private final JsonMapper jsonMapper;
    private final TraceContext traceContext;
    private final Map<String, Publisher> publishers = new ConcurrentHashMap<>();

    @Autowired
    public PubSubMessagePublisher(@Value("${bigquery.project-id}") String projectId,
                                  JsonMapper jsonMapper,
                                  TraceContext traceContext) {
        this.projectId = projectId;
        this.jsonMapper = jsonMapper;
        this.traceContext = traceContext;
    }

    @Override
    public String publish(String topic, MessagePayload payload) {
        return publish(topic, payload, Map.of());
    }

    @Override
    public String publish(String topic, MessagePayload payload, Map<String, String> attributes) {
        try {
            return publisher(topic).publish(message(payload, attributes))
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new MessagingException("Unable to publish event to " + topic, exception);
        }
    }

    PubsubMessage message(MessagePayload payload, Map<String, String> attributes) {
        PubsubMessage.Builder builder = PubsubMessage.newBuilder()
                .setData(ByteString.copyFrom(jsonMapper.writeValueAsBytes(payload)))
                .putAttributes("eventId", payload.eventId())
                .putAttributes("eventType", payload.eventType())
                .putAttributes("schemaVersion", payload.schemaVersion());
        traceContext.withCurrentTrace(attributes).forEach(builder::putAttributes);
        return builder.build();
    }

    private Publisher publisher(String topic) {
        return publishers.computeIfAbsent(topic, name -> {
            try {
                return Publisher.newBuilder(TopicName.of(projectId, name)).build();
            } catch (Exception exception) {
                throw new MessagingException("Unable to create Pub/Sub publisher for " + name, exception);
            }
        });
    }

    @Override
    @PreDestroy
    public void close() {
        publishers.forEach((topic, publisher) -> {
            publisher.shutdown();
            try {
                if (!publisher.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("Pub/Sub publisher for {} did not flush within {}s", topic,
                            SHUTDOWN_TIMEOUT_SECONDS);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while flushing Pub/Sub publisher for {}", topic);
            }
        });
        publishers.clear();
    }
}
