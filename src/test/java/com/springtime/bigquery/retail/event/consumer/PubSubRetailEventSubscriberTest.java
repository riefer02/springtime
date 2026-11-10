package com.springtime.bigquery.retail.event.consumer;

import com.google.cloud.pubsub.v1.AckReplyConsumer;
import com.google.pubsub.v1.PubsubMessage;
import com.google.protobuf.ByteString;
import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.retail.event.application.DeadLetterPublisher;
import com.springtime.bigquery.retail.event.application.RetailEventProcessor;
import com.springtime.bigquery.retail.event.model.ProcessingResult;
import com.springtime.bigquery.retail.event.model.TransientProcessingException;
import com.springtime.bigquery.observability.TraceContext;
import com.springtime.bigquery.observability.TracingTestSupport;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PubSubRetailEventSubscriberTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-11-09T12:00:00Z"), ZoneOffset.UTC);

    private final RetailEventProcessor processor = mock(RetailEventProcessor.class);
    private final DeadLetterPublisher deadLetters = mock(DeadLetterPublisher.class);
    private final AckReplyConsumer ack = mock(AckReplyConsumer.class);
    private final TracingTestSupport.CapturingSpanExporter exporter =
            new TracingTestSupport.CapturingSpanExporter();
    private final OpenTelemetrySdk sdk = TracingTestSupport.sdk(exporter);
    private final PubSubRetailEventSubscriber subscriber = new PubSubRetailEventSubscriber(
            new BigQueryProperties("test-project", "US", "", 1_000, 100, true, true, 2,
                    Duration.ofSeconds(1), Duration.ofSeconds(10)),
            new MessagingProperties(false, "retail-events", "retail-events-dlq",
                    "retail-events-worker", true, true, 500, 1_000),
            processor, deadLetters, JsonMapper.builder().build(), CLOCK,
            TraceContext.of(sdk));

    @AfterEach
    void tearDown() {
        sdk.close();
    }

    @Test
    void acksAfterSuccessfulProcessing() {
        when(processor.process(any())).thenReturn(ProcessingResult.PROCESSED);

        subscriber.receive(message(validEvent()), ack);

        verify(ack).ack();
        verify(ack, never()).nack();
    }

    @Test
    void nacksTransientFailure() {
        doThrow(new TransientProcessingException("backend down", null))
                .when(processor).process(any());

        subscriber.receive(message(validEvent()), ack);

        verify(ack).nack();
        verify(ack, never()).ack();
    }

    @Test
    void malformedEnvelopeIsDeadLetteredAndAcked() {
        subscriber.receive(message("not-json"), ack);

        verify(deadLetters).publish(any(), anyString(), anyInt(), any());
        verify(ack).ack();
    }

    @Test
    void malformedEnvelopeIsNackedWhenDeadLetterPublishFails() {
        doThrow(new IllegalStateException("dlq down"))
                .when(deadLetters).publish(any(), anyString(), anyInt(), any());

        subscriber.receive(message("not-json"), ack);

        verify(ack).nack();
        verify(ack, never()).ack();
    }

    @Test
    void usesBrokerTraceParentAsTheConsumerSpanParentWithoutChangingAck() {
        AtomicReference<String> observedTraceId = new AtomicReference<>();
        when(processor.process(any())).thenAnswer(invocation -> {
            observedTraceId.set(Span.current().getSpanContext().getTraceId());
            return ProcessingResult.PROCESSED;
        });
        PubsubMessage message = PubsubMessage.newBuilder()
                .setMessageId("msg-traced")
                .setData(ByteString.copyFromUtf8(validEvent()))
                .putAttributes("traceparent", TracingTestSupport.TRACEPARENT)
                .build();

        subscriber.receive(message, ack);

        verify(ack).ack();
        verify(ack, never()).nack();
        assertThat(observedTraceId).hasValue(TracingTestSupport.TRACE_ID);

        SpanData consumer = exporter.spans().stream()
                .filter(span -> "retail-event.consume".equals(span.getName()))
                .findFirst()
                .orElseThrow();
        assertThat(consumer.getParentSpanId()).isEqualTo(TracingTestSupport.PARENT_SPAN_ID);
        assertThat(consumer.getTraceId()).isEqualTo(TracingTestSupport.TRACE_ID);
    }

    @Test
    void nacksTransientFailureWhenTraceParentIsPresent() {
        doThrow(new TransientProcessingException("backend down", null))
                .when(processor).process(any());
        PubsubMessage message = PubsubMessage.newBuilder()
                .setMessageId("msg-traced")
                .setData(ByteString.copyFromUtf8(validEvent()))
                .putAttributes("traceparent", TracingTestSupport.TRACEPARENT)
                .build();

        subscriber.receive(message, ack);

        verify(ack).nack();
        verify(ack, never()).ack();
    }

    private static PubsubMessage message(String data) {
        return PubsubMessage.newBuilder()
                .setMessageId("msg-1")
                .setData(ByteString.copyFromUtf8(data))
                .build();
    }

    private static String validEvent() {
        return """
                {"eventId":"evt-1","eventType":"SALE",
                 "occurredAt":"2026-11-09T11:00:00Z","storeId":"ST-001",
                 "productId":"SKU-0001","quantity":2,"schemaVersion":"1","payload":{}}""";
    }
}
