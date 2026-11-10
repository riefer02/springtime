package com.springtime.bigquery.service;

import com.google.api.gax.paging.Page;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import com.google.cloud.bigquery.Schema;
import com.google.cloud.bigquery.Table;
import com.google.cloud.bigquery.TableDefinition;
import com.google.cloud.bigquery.TableId;
import com.springtime.bigquery.config.AppPaginationProperties;
import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.error.ResourceNotFoundException;
import com.springtime.bigquery.gateway.BigQueryGateway;
import com.springtime.bigquery.model.query.Column;
import com.springtime.bigquery.model.query.DatasetSummary;
import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.model.query.TableSchema;
import com.springtime.bigquery.model.query.TableSummary;
import com.springtime.bigquery.schema.SchemaHash;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Dataset, table, and schema catalog reads. Separate from query execution policy. */
@Service
public class CatalogService {

    private final BigQueryGateway gateway;
    private final BigQueryProperties properties;
    private final AppPaginationProperties paginationProperties;
    private final QueryResultMapper resultMapper;

    public CatalogService(BigQueryGateway gateway,
                          BigQueryProperties properties,
                          AppPaginationProperties paginationProperties,
                          QueryResultMapper resultMapper) {
        this.gateway = gateway;
        this.properties = properties;
        this.paginationProperties = paginationProperties;
        this.resultMapper = resultMapper;
    }

    public PageResponse<DatasetSummary> listDatasets(String pageToken, int pageSize) {
        int size = paginationProperties.clamp(pageSize);
        String projectId = properties.projectId();
        Page<Dataset> page = gateway.listDatasets(projectId, size, pageToken);
        List<DatasetSummary> datasets = new ArrayList<>();
        for (Dataset dataset : page.getValues()) {
            datasets.add(new DatasetSummary(
                    projectId,
                    dataset.getDatasetId().getDataset(),
                    dataset.getFriendlyName(),
                    dataset.getLocation()));
        }
        return new PageResponse<>(datasets, page.getNextPageToken(), size);
    }

    public PageResponse<TableSummary> listTables(String datasetId, String pageToken, int pageSize) {
        int size = paginationProperties.clamp(pageSize);
        DatasetId id = DatasetId.of(properties.projectId(), datasetId);
        Page<Table> page = gateway.listTables(id, size, pageToken);
        List<TableSummary> tables = new ArrayList<>();
        for (Table table : page.getValues()) {
            TableDefinition definition = table.getDefinition();
            String type = definition == null || definition.getType() == null
                    ? null
                    : definition.getType().name();
            Long created = table.getCreationTime();
            tables.add(new TableSummary(
                    table.getTableId().getTable(),
                    type,
                    created == null ? null : Instant.ofEpochMilli(created)));
        }
        return new PageResponse<>(tables, page.getNextPageToken(), size);
    }

    public TableSchema getTableSchema(String datasetId, String tableId) {
        String projectId = properties.projectId();
        TableId id = TableId.of(projectId, datasetId, tableId);
        Table table = gateway.getTable(id);
        if (table == null) {
            throw new ResourceNotFoundException(
                    "Table %s.%s.%s was not found".formatted(projectId, datasetId, tableId));
        }
        TableDefinition definition = table.getDefinition();
        Schema schema = definition == null ? null : definition.getSchema();
        if (schema == null) {
            throw new ResourceNotFoundException(
                    "Schema is not available for %s.%s.%s; the table type may be a model or external object"
                            .formatted(projectId, datasetId, tableId));
        }
        List<Column> columns = resultMapper.columns(schema);
        return new TableSchema(projectId, datasetId, tableId, columns, SchemaHash.of(columns));
    }
}
