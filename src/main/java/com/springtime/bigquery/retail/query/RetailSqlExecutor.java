package com.springtime.bigquery.retail.query;

import com.google.cloud.bigquery.BigQueryException;
import com.springtime.bigquery.config.RetailProperties;
import com.springtime.bigquery.error.DatasetNotProvisionedException;
import com.springtime.bigquery.model.QueryParameter;
import com.springtime.bigquery.model.query.QueryRequest;
import com.springtime.bigquery.model.query.QueryResponse;
import com.springtime.bigquery.service.BigQueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The single read path for owned retail SQL. It owns the byte budget, caching, job-label surface,
 * and 404 translation; adapters only supply a SQL name and parameters. No other retail class should
 * construct a {@link QueryRequest}.
 */
@Component
public class RetailSqlExecutor {

    private static final Logger log = LoggerFactory.getLogger(RetailSqlExecutor.class);
    private static final String SURFACE = "retail";

    private final BigQueryService bigQueryService;
    private final RetailSqlCatalog sqlCatalog;
    private final RetailProperties retailProperties;

    public RetailSqlExecutor(BigQueryService bigQueryService,
                             RetailSqlCatalog sqlCatalog,
                             RetailProperties retailProperties) {
        this.bigQueryService = bigQueryService;
        this.sqlCatalog = sqlCatalog;
        this.retailProperties = retailProperties;
    }

    public RetailQueryResult execute(RetailQuery query) {
        return execute(query.sqlName(), query.parameters(), query.maxResults());
    }

    /** Execute one fixed read. {@code maxResults} is the bound page size, not a server ceiling. */
    public RetailQueryResult execute(String sqlName, List<QueryParameter> parameters, long maxResults) {
        String sql = sqlCatalog.sql(sqlName);
        QueryResponse response;
        try {
            response = bigQueryService.executeInternal(SURFACE, new QueryRequest(
                    sql,
                    parameters,
                    maxResults,
                    retailProperties.maxBytesBilled(),
                    false,
                    true));
        } catch (BigQueryException exception) {
            if (exception.getCode() == 404) {
                throw new DatasetNotProvisionedException(
                        "Retail dataset or table is not available; run `make retail-schema` and `make retail-seed` first");
            }
            throw exception;
        }

        log.debug("retail query name={} job={} bytes={} cacheHit={}",
                sqlName, response.jobId(), response.bytesProcessed(), response.cacheHit());
        return new RetailQueryResult(response.rows());
    }
}
