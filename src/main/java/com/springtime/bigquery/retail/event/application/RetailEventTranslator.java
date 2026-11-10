package com.springtime.bigquery.retail.event.application;

import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.error.InvalidEventException;
import com.springtime.bigquery.retail.event.dto.EventEnvelope;
import com.springtime.bigquery.retail.event.dto.RetailEventRequest;
import com.springtime.bigquery.retail.event.model.EventValidationException;
import com.springtime.bigquery.retail.event.model.PermanentEventException;
import com.springtime.bigquery.retail.event.model.RetailEventType;
import com.springtime.bigquery.retail.inventory.model.InventoryMovement;
import com.springtime.bigquery.retail.inventory.model.InventoryMovementType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Translates an API event into a signed inventory movement.
 *
 * <p>The same policy runs at intake (fail the request) and in the consumer (dead-letter a poisoned
 * message). That keeps the public contract and the ledger rules from drifting.</p>
 */
@Component
public class RetailEventTranslator {

    static final String SUPPORTED_SCHEMA_VERSION = "1";
    private static final long MAX_ABSOLUTE_QUANTITY = 1_000_000L;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    private final RetailProperties properties;
    private final Clock clock;

    public RetailEventTranslator(RetailProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Validate before publishing so most poison events never reach the broker. */
    public void validateForSubmission(RetailEventRequest request) {
        try {
            validate(request.eventId(), request.storeId(), request.productId(),
                    request.eventType(), request.occurredAt(), request.quantity(), true);
            if (request.payload().size() > 50) {
                throw new EventValidationException("payload must contain at most 50 keys");
            }
            unitCost(request.payload());
            currency(request.payload());
            sourceDocumentId(request.payload(), request.eventId());
        } catch (EventValidationException exception) {
            throw new InvalidEventException(exception.getMessage());
        }
    }

    /** Validate and map a broker event to the ledger's signed movement shape. */
    public InventoryMovement translate(EventEnvelope event) {
        try {
            RetailEventType type = parseType(event.eventType());
            validate(event.eventId(), event.storeId(), event.productId(),
                    type, event.occurredAt(), event.quantity(), false);
            if (!SUPPORTED_SCHEMA_VERSION.equals(event.schemaVersion())) {
                throw new EventValidationException(
                        "Unsupported schemaVersion '%s'; expected '%s'"
                                .formatted(event.schemaVersion(), SUPPORTED_SCHEMA_VERSION));
            }

            Map<String, Object> payload = event.payload();
            if (payload.size() > 50) {
                throw new EventValidationException("payload must contain at most 50 keys");
            }
            long delta = signedDelta(type, event.quantity());
            return new InventoryMovement(
                    event.eventId(),
                    movementType(type),
                    event.occurredAt(),
                    event.occurredAt().atZone(properties.businessZone()).toLocalDate(),
                    event.storeId(),
                    event.productId(),
                    delta,
                    unitCost(payload),
                    currency(payload),
                    "retail-api",
                    sourceDocumentId(payload, event.eventId()),
                    event.schemaVersion(),
                    payload);
        } catch (EventValidationException exception) {
            throw new PermanentEventException("INVALID_EVENT", exception.getMessage(), exception);
        }
    }

    private void validate(String eventId, String storeId, String productId,
                          RetailEventType type, Instant occurredAt, Long quantity,
                          boolean enforceAgeWindow) {
        requireIdentifier(eventId, "eventId");
        requireIdentifier(storeId, "storeId");
        requireIdentifier(productId, "productId");
        if (type == null) {
            throw new EventValidationException("eventType is required");
        }
        if (occurredAt == null) {
            throw new EventValidationException("occurredAt is required");
        }
        validateFuture(occurredAt);
        if (enforceAgeWindow) {
            validateAge(occurredAt);
        }
        if (quantity == null) {
            throw new EventValidationException("quantity is required");
        }
        if (quantity < -MAX_ABSOLUTE_QUANTITY || quantity > MAX_ABSOLUTE_QUANTITY) {
            throw new EventValidationException(
                    "quantity must be between -%d and %d".formatted(MAX_ABSOLUTE_QUANTITY, MAX_ABSOLUTE_QUANTITY));
        }
        switch (type) {
            case SALE, RETURN, SHIPMENT, OPENING_BALANCE -> {
                if (quantity <= 0) {
                    throw new EventValidationException(
                            type.name() + " quantity must be positive");
                }
            }
            case INVENTORY_ADJUSTMENT -> {
                if (quantity == 0) {
                    throw new EventValidationException(
                            "INVENTORY_ADJUSTMENT quantity must not be zero");
                }
            }
        }
    }

    private void validateFuture(Instant occurredAt) {
        if (occurredAt.isAfter(clock.instant().plus(properties.eventMaxFutureSkew()))) {
            throw new EventValidationException(
                    "occurredAt is too far in the future (max skew "
                            + properties.eventMaxFutureSkew() + ")");
        }
    }

    private void validateAge(Instant occurredAt) {
        if (occurredAt.isBefore(clock.instant().minus(properties.eventMaxAge()))) {
            throw new EventValidationException(
                    "occurredAt is older than the allowed window (" + properties.eventMaxAge() + ")");
        }
    }

    private static RetailEventType parseType(String value) {
        try {
            return RetailEventType.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new EventValidationException("eventType must be one of SALE, RETURN, "
                    + "INVENTORY_ADJUSTMENT, SHIPMENT, OPENING_BALANCE");
        }
    }

    private static InventoryMovementType movementType(RetailEventType type) {
        return switch (type) {
            case SALE -> InventoryMovementType.SALE;
            case RETURN -> InventoryMovementType.RETURN;
            case INVENTORY_ADJUSTMENT -> InventoryMovementType.ADJUSTMENT;
            case SHIPMENT -> InventoryMovementType.SHIPMENT;
            case OPENING_BALANCE -> InventoryMovementType.OPENING_BALANCE;
        };
    }

    private static long signedDelta(RetailEventType type, long quantity) {
        return switch (type) {
            case SALE -> -quantity;
            case RETURN, SHIPMENT, INVENTORY_ADJUSTMENT, OPENING_BALANCE -> quantity;
        };
    }

    private BigDecimal unitCost(Map<String, Object> payload) {
        Object value = payload.get("unitCost");
        if (value == null) {
            return null;
        }
        try {
            BigDecimal cost = value instanceof Number number
                    ? new BigDecimal(number.toString())
                    : new BigDecimal(String.valueOf(value).trim());
            if (cost.signum() < 0) {
                throw new EventValidationException("unitCost must not be negative");
            }
            if (cost.precision() > 38 || cost.scale() > 9) {
                throw new EventValidationException("unitCost exceeds NUMERIC precision/scale");
            }
            return cost;
        } catch (NumberFormatException exception) {
            throw new EventValidationException("unitCost must be a decimal number");
        }
    }

    private String currency(Map<String, Object> payload) {
        Object value = payload.get("currency");
        String currency = value == null ? properties.currency() : String.valueOf(value).trim().toUpperCase();
        if (!CURRENCY.matcher(currency).matches()) {
            throw new EventValidationException("currency must be a three-letter ISO code");
        }
        return currency;
    }

    private static String sourceDocumentId(Map<String, Object> payload, String fallback) {
        Object value = payload.get("sourceDocumentId");
        if (value == null) {
            return fallback;
        }
        String candidate = String.valueOf(value);
        if (!SAFE_IDENTIFIER.matcher(candidate).matches()) {
            throw new EventValidationException(
                    "sourceDocumentId must match [A-Za-z0-9._:-]{1,64}");
        }
        return candidate;
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new EventValidationException(name + " must match [A-Za-z0-9._:-]{1,64}");
        }
    }
}
