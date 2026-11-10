package com.springtime.bigquery.retail.replenishment.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/** Request to create or replace one replenishment run. */
public record ReplenishmentRunRequest(
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "runId has an unsupported format")
        String runId,
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "storeId has an unsupported format")
        String storeId,
        LocalDate asOfDate,
        @Min(value = 1, message = "demandWindowDays must be positive")
        @Max(value = 365, message = "demandWindowDays must be at most 365")
        Integer demandWindowDays,
        @Min(value = 1, message = "targetCoverDays must be positive")
        @Max(value = 365, message = "targetCoverDays must be at most 365")
        Integer targetCoverDays
) {
}
