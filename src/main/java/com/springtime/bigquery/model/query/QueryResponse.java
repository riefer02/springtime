package com.springtime.bigquery.model.query;

import java.util.List;
import java.util.Map;

/**
 * Result of a query job.
 *
 * @param jobId          BigQuery job id, also returned in the {@code X-BigQuery-Job-Id} header
 * @param schema         column metadata, empty for dry runs
 * @param rows           row objects keyed by column name
 * @param returnedRows   number of rows in {@code rows}
 * @param totalRows      total rows matched by the query, when BigQuery reports it
 * @param bytesProcessed bytes the query would process / did process
 * @param bytesBilled    bytes actually billed (0 for dry runs and cache hits)
 * @param cacheHit       whether the result cache answered the query
 * @param dryRun         whether this was a dry run
 * @param nextPageToken  token for the next page, or null when the byte budget truncated this page
 * @param truncated      true when rows were omitted to respect {@code MAX_RESPONSE_BYTES}
 */
public record QueryResponse(
        String jobId,
        List<Column> schema,
        List<Map<String, Object>> rows,
        long returnedRows,
        Long totalRows,
        long bytesProcessed,
        long bytesBilled,
        boolean cacheHit,
        boolean dryRun,
        String nextPageToken,
        boolean truncated
) {
}
