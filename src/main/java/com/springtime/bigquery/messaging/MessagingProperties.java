package com.springtime.bigquery.messaging;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Event topic, subscription, consumer, and local-broker settings. */
@Validated
@ConfigurationProperties(prefix = "messaging")
public record MessagingProperties(
        @DefaultValue("false") boolean pubsubEnabled,
        @DefaultValue("retail-events") @NotBlank String eventsTopic,
        @DefaultValue("retail-events-dlq") @NotBlank String deadLetterTopic,
        @DefaultValue("retail-events-worker") @NotBlank String subscription,
        @DefaultValue("true") boolean consumerEnabled,
        @DefaultValue("true") boolean localConsumerEnabled,
        @DefaultValue("500") @Min(50) @Max(60_000) long localConsumerPollIntervalMs,
        @DefaultValue("1000") @Min(1) @Max(100_000) int maxInMemoryMessages
) {
}
