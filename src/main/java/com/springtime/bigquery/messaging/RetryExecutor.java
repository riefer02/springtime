package com.springtime.bigquery.messaging;

import com.springtime.bigquery.util.Backoff;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Bounded retry with exponential backoff and proportional jitter.
 *
 * <p>Callers should pass a predicate that classifies transient failures. Retrying every
 * {@link RuntimeException} turns poison messages and programming bugs into avoidable latency.</p>
 */
@Component
public class RetryExecutor {

    private final RetryProperties properties;
    private final Sleeper sleeper;

    public RetryExecutor(RetryProperties properties, Sleeper sleeper) {
        this.properties = properties;
        this.sleeper = sleeper;
    }

    public <T> T execute(String operation, Supplier<T> action) {
        return execute(operation, action, exception -> true);
    }

    public <T> T execute(String operation, Supplier<T> action, Predicate<RuntimeException> retryable) {
        RuntimeException last = null;
        boolean retryableFailure = true;
        for (int attempt = 1; attempt <= properties.maxAttempts(); attempt++) {
            try {
                return action.get();
            } catch (RuntimeException exception) {
                last = exception;
                retryableFailure = retryable.test(exception);
                if (!retryableFailure || attempt == properties.maxAttempts()) {
                    break;
                }
                sleeper.sleep(Duration.ofMillis(delayMillis(attempt)));
            }
        }
        if (last != null && !retryableFailure) {
            throw last;
        }
        throw new RetryExhaustedException(operation, properties.maxAttempts(), last);
    }

    long delayMillis(int attempt) {
        return Backoff.delayMillis(attempt, properties.initialDelay(), properties.multiplier(),
                properties.maxDelay(), properties.jitter());
    }
}
