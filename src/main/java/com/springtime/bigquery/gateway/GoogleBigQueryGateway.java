package com.springtime.bigquery.gateway;

import com.google.api.gax.paging.Page;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQuery.DatasetListOption;
import com.google.cloud.bigquery.BigQuery.QueryResultsOption;
import com.google.cloud.bigquery.BigQuery.TableListOption;
import com.google.cloud.bigquery.BigQuery.TableOption;
import com.google.cloud.bigquery.BigQuery.TableField;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Job;
import com.google.cloud.bigquery.JobId;
import com.google.cloud.bigquery.JobInfo;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableResult;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
class GoogleBigQueryGateway implements BigQueryGateway {

    private final BigQuery bigQuery;

    GoogleBigQueryGateway(@Lazy BigQuery bigQuery) {
        this.bigQuery = bigQuery;
    }

    @Override
    public Job create(JobInfo jobInfo) {
        return bigQuery.create(jobInfo);
    }

    @Override
    public Job getJob(JobId jobId) {
        return bigQuery.getJob(jobId);
    }

    @Override
    public TableResult getQueryResults(Job job, long pageSize, String pageToken) throws InterruptedException {
        List<QueryResultsOption> options = new ArrayList<>();
        options.add(QueryResultsOption.pageSize(pageSize));
        if (StringUtils.hasText(pageToken)) {
            options.add(QueryResultsOption.pageToken(pageToken));
        }
        return job.getQueryResults(options.toArray(QueryResultsOption[]::new));
    }

    @Override
    public Page<Dataset> listDatasets(String projectId, long pageSize, String pageToken) {
        List<DatasetListOption> options = new ArrayList<>();
        options.add(DatasetListOption.pageSize(pageSize));
        if (StringUtils.hasText(pageToken)) {
            options.add(DatasetListOption.pageToken(pageToken));
        }
        return bigQuery.listDatasets(projectId, options.toArray(DatasetListOption[]::new));
    }

    @Override
    public Page<Table> listTables(DatasetId datasetId, long pageSize, String pageToken) {
        List<TableListOption> options = new ArrayList<>();
        options.add(TableListOption.pageSize(pageSize));
        if (StringUtils.hasText(pageToken)) {
            options.add(TableListOption.pageToken(pageToken));
        }
        return bigQuery.listTables(datasetId, options.toArray(TableListOption[]::new));
    }

    @Override
    public Table getTable(TableId tableId) {
        return bigQuery.getTable(tableId, TableOption.fields(TableField.SCHEMA));
    }
}
