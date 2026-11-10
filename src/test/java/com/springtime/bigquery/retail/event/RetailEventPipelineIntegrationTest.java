package com.springtime.bigquery.retail.event;

import com.springtime.bigquery.messaging.InMemoryMessagePublisher;
import com.springtime.bigquery.retail.event.dto.RetailEventRequest;
import com.springtime.bigquery.retail.event.application.RetailEventPublisher;
import com.springtime.bigquery.retail.event.consumer.LocalRetailEventRelay;
import com.springtime.bigquery.retail.event.model.RetailEventType;
import com.springtime.bigquery.retail.inventory.model.InventoryMovement;
import com.springtime.bigquery.retail.inventory.model.MovementWriteResult;
import com.springtime.bigquery.retail.inventory.application.InventoryMovementSink;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the local pipeline is wired end to end: publisher -> bounded in-memory broker -> relay ->
 * translator -> sink. It uses a fake sink so no GCP credentials are required.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.security.api-key=pipeline-key-0123456789abcdef0123456789",
                "bigquery.project-id=pipeline-project",
                "retail.ledger.batch.enabled=false",
                "messaging.local-consumer-enabled=true",
                "messaging.local-consumer-poll-interval-ms=60000"
        })
@Import(RetailEventPipelineIntegrationTest.RecordingSinkConfig.class)
@ActiveProfiles("test")
class RetailEventPipelineIntegrationTest {

    @Autowired
    private RetailEventPublisher publisher;

    @Autowired
    private LocalRetailEventRelay relay;

    @Autowired
    private InMemoryMessagePublisher broker;

    @Autowired
    private RecordingSink sink;

    @Test
    void publishesProcessesAndAcknowledgesOneEvent() {
        broker.clear();
        sink.movements.clear();

        publisher.publish(new RetailEventRequest(
                "evt-pipeline-1",
                RetailEventType.SALE,
                Instant.now().minusSeconds(60),
                "ST-001",
                "SKU-0001",
                3L,
                Map.of("sourceDocumentId", "POS-1")));

        relay.drain();

        assertThat(sink.movements).singleElement().satisfies(movement -> {
            assertThat(movement.eventId()).isEqualTo("evt-pipeline-1");
            assertThat(movement.quantityDelta()).isEqualTo(-3L);
            assertThat(movement.sourceDocumentId()).isEqualTo("POS-1");
        });
        assertThat(broker.publishedEvents()).isEmpty();
        assertThat(broker.deadLetters()).isEmpty();
    }

    @TestConfiguration
    static class RecordingSinkConfig {

        @Bean
        @Primary
        RecordingSink recordingSink() {
            return new RecordingSink();
        }
    }

    static class RecordingSink implements InventoryMovementSink {

        private final List<InventoryMovement> movements = new CopyOnWriteArrayList<>();

        @Override
        public MovementWriteResult write(InventoryMovement movement) {
            movements.add(movement);
            return MovementWriteResult.INSERTED;
        }
    }
}
