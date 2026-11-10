package com.springtime.bigquery.messaging;

import java.time.Duration;

/** Test seam for retry delays. */
public interface Sleeper {

    void sleep(Duration duration);
}
