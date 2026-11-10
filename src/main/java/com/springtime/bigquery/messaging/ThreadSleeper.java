package com.springtime.bigquery.messaging;

import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ThreadSleeper implements Sleeper {

    @Override
    public void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RetryInterruptedException(exception);
        }
    }
}
