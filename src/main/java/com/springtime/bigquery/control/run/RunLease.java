package com.springtime.bigquery.control.run;

import java.time.Duration;

/**
 * Cross-instance lease for a long-running replenishment run.
 *
 * <p>Replenishment creation is a multi-job BigQuery sequence. The lease serializes callers for the
 * same {@code runId} without holding a database transaction across those jobs.</p>
 */
public interface RunLease {

    boolean tryAcquire(String runId, String owner, Duration ttl);

    void release(String runId, String owner);
}
