package com.springtime.bigquery.messaging;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetryExecutorTest {

    @Test
    void retriesWithExponentialBackoff() {
        RecordingSleeper sleeper = new RecordingSleeper();
        RetryExecutor executor = new RetryExecutor(
                new RetryProperties(3, Duration.ofMillis(100), 2.0, Duration.ofSeconds(1), 0.0),
                sleeper);
        AtomicInteger attempts = new AtomicInteger();

        String result = executor.execute("test", () -> {
            if (attempts.incrementAndGet() < 3) {
                throw new IllegalStateException("transient");
            }
            return "ok";
        });

        assertThat(result).isEqualTo("ok");
        assertThat(attempts).hasValue(3);
        assertThat(sleeper.durations).containsExactly(
                Duration.ofMillis(100), Duration.ofMillis(200));
    }

    @Test
    void throwsAfterMaxAttempts() {
        RetryExecutor executor = new RetryExecutor(
                new RetryProperties(2, Duration.ofMillis(10), 2.0, Duration.ofMillis(20), 0.0),
                duration -> {
                });

        assertThatThrownBy(() -> executor.execute("test", () -> {
            throw new IllegalStateException("permanent");
        }))
                .isInstanceOf(RetryExhaustedException.class)
                .hasMessageContaining("attempt(s)")
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    void jitterStaysWithinTheConfiguredBand() {
        RetryExecutor executor = new RetryExecutor(
                new RetryProperties(5, Duration.ofMillis(100), 2.0, Duration.ofSeconds(10), 0.2),
                duration -> {
                });

        for (int attempt = 1; attempt <= 5; attempt++) {
            long delay = executor.delayMillis(attempt);
            assertThat(delay).isBetween(0L, 10_000L);
        }
    }

    private static final class RecordingSleeper implements Sleeper {
        private final List<Duration> durations = new ArrayList<>();

        @Override
        public void sleep(Duration duration) {
            durations.add(duration);
        }
    }
}
