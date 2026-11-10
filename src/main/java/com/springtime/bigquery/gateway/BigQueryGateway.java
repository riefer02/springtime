package com.springtime.bigquery.gateway;

import com.google.api.gax.paging.Page;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableResult;

/**
 * Application boundary over the BigQuery resource client. Services depend on this port rather than
 * calling the SDK directly; the implementation owns transport, pagination options, and schema
 * field selection. BigQuery is the data plane; this is not an entity repository.
 */
public interface BigQueryGateway {

    Job create(JobInfo jobInfo);

    Job getJob(JobId jobId);

    TableResult getQueryResults(Job job, long pageSize, String pageToken) throws InterruptedException;

    Page<Dataset> listDatasets(String projectId, long pageSize, String pageToken);

    Page<Table> listTables(DatasetId datasetId, long pageSize, String pageToken);

    Table getTable(TableId tableId);
}
