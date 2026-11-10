package com.springtime.bigquery.messaging;

import com.springtime.bigquery.observability.TraceContext;
import com.springtime.bigquery.observability.TracingTestSupport;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InMemoryMessagePublisherTest {

    private static final MessagingProperties PROPERTIES = new MessagingProperties(
            false, "events", "events-dlq", "subscription", true, true, 500, 2);

    @Test
    void failsClosedWhenCapacityIsReached() {
        InMemoryMessagePublisher publisher = new InMemoryMessagePublisher(PROPERTIES);
        publisher.publish("events", event("evt-1"));
        publisher.publish("events", event("evt-2"));

        assertThatThrownBy(() -> publisher.publish("events", event("evt-3")))
                .isInstanceOf(MessagingException.class)
                .hasMessageContaining("full");
    }

    @Test
    void storesDeadLettersWithoutDeliveringThemAsEvents() {
        InMemoryMessagePublisher publisher = new InMemoryMessagePublisher(PROPERTIES);
        publisher.publish("events-dlq", event("evt-dlq"));

        assertThat(publisher.poll()).isEmpty();
        assertThat(publisher.deadLetters()).hasSize(1);
        assertThat(publisher.deadLetters().get(0).payload().eventId()).isEqualTo("evt-dlq");
    }

    @Test
    void pollAndRequeueSupportTheLocalRelay() {
        InMemoryMessagePublisher publisher = new InMemoryMessagePublisher(PROPERTIES);
        publisher.publish("events", event("evt-1"));

        InMemoryMessagePublisher.PublishedMessage message = publisher.poll().orElseThrow();
        assertThat(message.payload().eventId()).isEqualTo("evt-1");
        assertThat(publisher.poll()).isEmpty();

        publisher.requeue(message);
        assertThat(publisher.poll()).contains(message);
    }

    @Test
    void publishCarriesTraceParentFromTheCurrentSpan() {
        TracingTestSupport.CapturingSpanExporter exporter =
                new TracingTestSupport.CapturingSpanExporter();
        OpenTelemetrySdk sdk = TracingTestSupport.sdk(exporter);
        InMemoryMessagePublisher publisher = new InMemoryMessagePublisher(
                PROPERTIES, TraceContext.of(sdk));
        Span span = sdk.getTracer("test").spanBuilder("publish").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            publisher.publish("events", event("evt-trace"));
        } finally {
            span.end();
            sdk.close();
        }

        InMemoryMessagePublisher.PublishedMessage message = publisher.poll().orElseThrow();
        assertThat(message.attributes()).containsKey("traceparent");
        assertThat(message.attributes().get("traceparent"))
                .startsWith("00-" + span.getSpanContext().getTraceId() + "-");
    }

    @Test
    void publishKeepsExplicitAttributesWhenNoSpanIsActive() {
        InMemoryMessagePublisher publisher = new InMemoryMessagePublisher(PROPERTIES);

        publisher.publish("events", event("evt-attributes"), java.util.Map.of("custom", "value"));

        assertThat(publisher.poll().orElseThrow().attributes())
                .containsEntry("custom", "value");
    }

    private static MessagePayload event(String eventId) {
        return new TestPayload(eventId);
    }

    private record TestPayload(String eventId) implements MessagePayload {
        @Override
        public String eventType() {
            return "SALE";
        }

        @Override
        public String schemaVersion() {
            return "1";
        }
    }
}
