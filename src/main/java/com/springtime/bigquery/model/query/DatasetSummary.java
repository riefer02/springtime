package com.springtime.bigquery.model.query;

/** Summary of a dataset visible to the caller. */
public record DatasetSummary(
        String projectId,
        String datasetId,
        String friendlyName,
        String location
) {
}
