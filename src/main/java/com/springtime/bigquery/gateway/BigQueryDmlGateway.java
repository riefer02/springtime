package com.springtime.bigquery.gateway;

import com.google.cloud.bigquery.QueryParameterValue;

import java.util.Map;

/**
 * Narrow write boundary for controlled DML. Read traffic goes through {@link BigQueryGateway} and
 * the read-only query service.
 *
 * <p>Deliberate users: the inventory ledger sink, dead-letter persistence/replay, replenishment
 * run/proposal writes, and projection refresh. Do not expose this port to the generic query path.</p>
 */
public interface BigQueryDmlGateway {

    /**
     * Execute one parameterized DML statement and return the number of affected rows.
     */
    long execute(String sql, Map<String, QueryParameterValue> parameters, Map<String, String> labels);

    /**
     * Execute an idempotent merge/insert where zero affected rows is a valid outcome. Adapters may
     * treat a missing affected-row statistic as zero instead of an error.
     */
    default long executeIdempotent(String sql, Map<String, QueryParameterValue> parameters,
                                   Map<String, String> labels) {
        return execute(sql, parameters, labels);
    }

    /**
     * Execute a multi-statement DML script (for example a BigQuery transaction) where the caller
     * needs all statements to commit or roll back together. The default keeps simple test adapters
     * source-compatible; production adapters must override it.
     */
    default void executeTransaction(String sql, Map<String, QueryParameterValue> parameters,
                                    Map<String, String> labels) {
        throw new UnsupportedOperationException("Transactional DML is not supported by this adapter");
    }
}
