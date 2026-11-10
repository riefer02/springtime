package com.springtime.bigquery.control.run;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Postgres-backed run lease. Expiry makes a crashed owner recoverable; an expired lease can be
 * acquired by the next caller with the same {@code runId}.
 */
@Component
public class JdbcRunLease implements RunLease {

    private final JdbcClient jdbcClient;
    private final Clock clock;

    public JdbcRunLease(JdbcClient jdbcClient, Clock clock) {
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    @Override
    public boolean tryAcquire(String runId, String owner, Duration ttl) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        int changed = jdbcClient.sql("""
                INSERT INTO control_run_leases (run_id, owner, acquired_at, expires_at)
                VALUES (:runId, :owner, :now, :expiresAt)
                ON CONFLICT (run_id) DO UPDATE
                  SET owner = EXCLUDED.owner,
                      acquired_at = EXCLUDED.acquired_at,
                      expires_at = EXCLUDED.expires_at
                  WHERE control_run_leases.expires_at < :now
                     OR control_run_leases.owner = EXCLUDED.owner
                """)
                .param("runId", runId)
                .param("owner", owner)
                .param("now", Timestamp.from(now))
                .param("expiresAt", Timestamp.from(expiresAt))
                .update();
        if (changed == 1) {
            return true;
        }
        return jdbcClient.sql("""
                        SELECT owner FROM control_run_leases
                        WHERE run_id = :runId AND expires_at >= :now
                        """)
                .param("runId", runId)
                .param("now", Timestamp.from(now))
                .query(String.class)
                .optional()
                .map(owner::equals)
                .orElse(false);
    }

    @Override
    public void release(String runId, String owner) {
        jdbcClient.sql("DELETE FROM control_run_leases WHERE run_id = :runId AND owner = :owner")
                .param("runId", runId)
                .param("owner", owner)
                .update();
    }
}
