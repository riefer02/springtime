package com.springtime.bigquery.retail.event.dto;

import com.springtime.bigquery.retail.event.model.RetailEventType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A retail event accepted for asynchronous processing.
 *
 * <p>{@code quantity} is positive for SALE, RETURN, SHIPMENT, and OPENING_BALANCE.
 * INVENTORY_ADJUSTMENT uses a signed value so a correction can increase or decrease on-hand without
 * a second field.</p>
 */
public record RetailEventRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "eventId has an unsupported format")
        String eventId,
        @NotNull RetailEventType eventType,
        @NotNull Instant occurredAt,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "storeId has an unsupported format")
        String storeId,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "productId has an unsupported format")
        String productId,
        @NotNull
        @Min(value = -1_000_000, message = "quantity is outside the supported range")
        @Max(value = 1_000_000, message = "quantity is outside the supported range")
        Long quantity,
        @Size(max = 50) Map<String, Object> payload
) {
    public RetailEventRequest {
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
