package com.springtime.bigquery.retail.event.application;

import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.error.InvalidEventException;
import com.springtime.bigquery.retail.event.dto.EventEnvelope;
import com.springtime.bigquery.retail.event.dto.RetailEventRequest;
import com.springtime.bigquery.retail.event.model.PermanentEventException;
import com.springtime.bigquery.retail.event.model.RetailEventType;
import com.springtime.bigquery.retail.inventory.model.InventoryMovement;
import com.springtime.bigquery.retail.inventory.model.InventoryMovementType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetailEventTranslatorTest {

    private static final Instant NOW = Instant.parse("2026-11-09T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final RetailProperties PROPERTIES = new RetailProperties(
            "retail_demo", 50, 200, 28, 14, 268_435_456L,
            "USD", Duration.ofMinutes(5), Duration.ofDays(365), 0, java.time.ZoneOffset.UTC);

    private final RetailEventTranslator translator = new RetailEventTranslator(PROPERTIES, CLOCK);

    @Test
    void mapsSaleToNegativeMovement() {
        InventoryMovement movement = translator.translate(event("SALE", 3L, Map.of()));

        assertThat(movement.movementType()).isEqualTo(InventoryMovementType.SALE);
        assertThat(movement.quantityDelta()).isEqualTo(-3L);
        assertThat(movement.businessDate()).isEqualTo(NOW.atZone(ZoneOffset.UTC).toLocalDate());
        assertThat(movement.currency()).isEqualTo("USD");
        assertThat(movement.sourceSystem()).isEqualTo("retail-api");
    }

    @Test
    void mapsReturnAndShipmentToPositiveMovements() {
        assertThat(translator.translate(event("RETURN", 2L, Map.of())).quantityDelta()).isEqualTo(2L);
        assertThat(translator.translate(event("SHIPMENT", 7L, Map.of())).quantityDelta()).isEqualTo(7L);
    }

    @Test
    void derivesTheBusinessDateInTheConfiguredZone() {
        RetailEventTranslator eastern = new RetailEventTranslator(
                new RetailProperties("retail_demo", 50, 200, 28, 14, 268_435_456L,
                        "USD", Duration.ofMinutes(5), Duration.ofDays(365), 0,
                        java.time.ZoneId.of("America/New_York")),
                CLOCK);

        InventoryMovement movement = eastern.translate(eventAt(
                "SALE", Instant.parse("2026-11-09T02:00:00Z")));

        assertThat(movement.businessDate()).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @Test
    void mapsOpeningBalanceToAPositiveMovement() {
        InventoryMovement movement = translator.translate(event("OPENING_BALANCE", 4_000L, Map.of()));

        assertThat(movement.movementType()).isEqualTo(InventoryMovementType.OPENING_BALANCE);
        assertThat(movement.quantityDelta()).isEqualTo(4_000L);
    }

    @Test
    void preservesSignedAdjustment() {
        InventoryMovement movement = translator.translate(event("INVENTORY_ADJUSTMENT", -4L, Map.of()));

        assertThat(movement.movementType()).isEqualTo(InventoryMovementType.ADJUSTMENT);
        assertThat(movement.quantityDelta()).isEqualTo(-4L);
    }

    @Test
    void readsCostCurrencyAndSourceDocumentFromPayload() {
        InventoryMovement movement = translator.translate(event("SALE", 1L, Map.of(
                "unitCost", "1.25",
                "currency", "eur",
                "sourceDocumentId", "POS-123")));

        assertThat(movement.unitCost()).isEqualByComparingTo(new BigDecimal("1.25"));
        assertThat(movement.currency()).isEqualTo("EUR");
        assertThat(movement.sourceDocumentId()).isEqualTo("POS-123");
    }

    @Test
    void rejectsFutureEventsAtConsumeTime() {
        assertThatThrownBy(() -> translator.translate(eventAt("SALE", NOW.plus(Duration.ofMinutes(10)))))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("future");
    }

    @Test
    void acceptsOldEventsAtConsumeTimeSoDeadLettersCanBeReplayed() {
        InventoryMovement old = translator.translate(
                eventAt("SALE", NOW.minus(Duration.ofDays(400))));

        assertThat(old.occurredAt()).isBefore(NOW.minus(Duration.ofDays(399)));
    }

    @Test
    void rejectsStaleEventsAtSubmissionTime() {
        RetailEventRequest request = new RetailEventRequest(
                "evt-1", RetailEventType.SALE, NOW.minus(Duration.ofDays(400)),
                "ST-001", "SKU-0001", 1L, Map.of());

        assertThatThrownBy(() -> translator.validateForSubmission(request))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("older");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        EventEnvelope event = new EventEnvelope(
                "evt-1", "SALE", NOW.minusSeconds(60), "2",
                "ST-001", "SKU-0001", 1L, Map.of());

        assertThatThrownBy(() -> translator.translate(event))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void rejectsZeroOrNegativeSaleQuantity() {
        assertThatThrownBy(() -> translator.translate(event("SALE", 0L, Map.of())))
                .isInstanceOf(PermanentEventException.class);
        assertThatThrownBy(() -> translator.translate(event("SALE", -1L, Map.of())))
                .isInstanceOf(PermanentEventException.class);
        assertThatThrownBy(() -> translator.translate(event("INVENTORY_ADJUSTMENT", 0L, Map.of())))
                .isInstanceOf(PermanentEventException.class);
    }

    @Test
    void rejectsUnsafeIdentifiers() {
        EventEnvelope event = new EventEnvelope(
                "evt\nforged", "SALE", NOW.minusSeconds(60), "1",
                "ST-001", "SKU-0001", 1L, Map.of());

        assertThatThrownBy(() -> translator.translate(event))
                .isInstanceOf(PermanentEventException.class)
                .hasMessageContaining("eventId");
    }

    @Test
    void intakeValidationFailsFast() {
        RetailEventRequest request = new RetailEventRequest(
                "evt-1", RetailEventType.SALE, NOW.minusSeconds(60),
                "ST-001", null, 1L, Map.of());

        assertThatThrownBy(() -> translator.validateForSubmission(request))
                .isInstanceOf(InvalidEventException.class)
                .hasMessageContaining("productId");
    }

    private static EventEnvelope event(String type, long quantity, Map<String, Object> payload) {
        return new EventEnvelope(
                "evt-1", type, NOW.minusSeconds(60), "1",
                "ST-001", "SKU-0001", quantity, payload);
    }

    private static EventEnvelope eventAt(String type, Instant occurredAt) {
        return new EventEnvelope(
                "evt-1", type, occurredAt, "1",
                "ST-001", "SKU-0001", 1L, Map.of());
    }
}
