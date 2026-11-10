package com.springtime.bigquery.messaging;

import com.springtime.bigquery.observability.TraceContext;
import com.springtime.bigquery.observability.TracingTestSupport;
import com.google.pubsub.v1.PubsubMessage;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PubSubMessagePublisherTest {

    @Test
    void addsTraceParentToPubSubAttributesForTheCurrentSpan() {
        TracingTestSupport.CapturingSpanExporter exporter =
                new TracingTestSupport.CapturingSpanExporter();
        OpenTelemetrySdk sdk = TracingTestSupport.sdk(exporter);
        PubSubMessagePublisher publisher = new PubSubMessagePublisher(
                "test-project", JsonMapper.builder().build(), TraceContext.of(sdk));
        Span span = sdk.getTracer("test").spanBuilder("publish").startSpan();
        try (Scope ignored = span.makeCurrent()) {
            PubsubMessage message = publisher.message(payload(), Map.of("custom", "value"));

            assertThat(message.getAttributesMap())
                    .containsEntry("eventId", "evt-1")
                    .containsEntry("custom", "value")
                    .containsKey("traceparent");
            assertThat(message.getAttributesMap().get("traceparent"))
                    .startsWith("00-" + span.getSpanContext().getTraceId() + "-");
        } finally {
            span.end();
            sdk.close();
        }
    }

    private static MessagePayload payload() {
        return new MessagePayload() {
            @Override
            public String eventId() {
                return "evt-1";
            }

            @Override
            public String eventType() {
                return "SALE";
            }

            @Override
            public String schemaVersion() {
                return "1";
            }
        };
    }
}
