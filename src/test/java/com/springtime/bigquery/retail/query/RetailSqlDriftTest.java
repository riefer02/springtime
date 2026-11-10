package com.springtime.bigquery.retail.query;

import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.config.RetailProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the KPI/recommendation formula shared by the worklist and durable proposal queries. Both
 * queries render the same partials, so the extracted fragments must match after whitespace
 * normalization (the partial is inserted verbatim, so only indentation can differ).
 */
class RetailSqlDriftTest {

    private static final String PROJECT = "project-x";
    private static final String DATASET = "retail_ds";

    private static final RetailProperties RETAIL = new RetailProperties(
            DATASET, 50, 200, 28, 14, 1_000L, "USD",
            Duration.ofMinutes(5), Duration.ofDays(365), 0, ZoneOffset.UTC);

    private static final RetailSqlCatalog CATALOG = new RetailSqlCatalog(
            new DefaultResourceLoader(),
            new BigQueryProperties(PROJECT, "US", "", 1_000, 100, true, true, 2,
                    Duration.ofSeconds(1), Duration.ofSeconds(10)),
            RETAIL);

    private static final String[] SHARED_PREAMBLE_CONSUMERS = {
            "inventory_risk", "replenishment", "replenishment_proposals_merge"};

    private static final String[] ALL_PREAMBLE_CONSUMERS = {
            "inventory_risk", "replenishment", "replenishment_proposals_merge", "dashboard_summary"};

    private static final String[] SHARED_FORMULA_CONSUMERS = {
            "replenishment", "replenishment_proposals_merge"};

    @Test
    void worklistAndProposalFormulaAreIdenticalByConstruction() throws IOException {
        String worklist = formula(CATALOG.sql("replenishment"), "replenishment");
        String proposals = formula(CATALOG.sql("replenishment_proposals_merge"), "replenishment_proposals_merge");

        assertThat(proposals)
                .as("replenishment worklist and durable proposal formula drifted")
                .isEqualTo(worklist);
        assertThat(worklist)
                .as("rendered formula must be the shared recommendation_formula partial")
                .isEqualTo(compact(partial("recommendation_formula")));
    }

    @Test
    void worklistAndProposalShareTheSameCandidateSet() throws IOException {
        String worklist = between(CATALOG.sql("replenishment"), "enriched AS (", "targeted AS (");
        String proposals = between(
                CATALOG.sql("replenishment_proposals_merge"), "enriched AS (", "targeted AS (");

        assertThat(proposals)
                .as("worklist and durable proposal candidate pipeline drifted")
                .isEqualTo(worklist);
        assertThat(worklist)
                .as("candidate pipeline must be the shared replenishment_candidates partial")
                .isEqualTo(compact(partial("replenishment_candidates")));
        for (String name : new String[]{"replenishment", "replenishment_proposals_merge"}) {
            assertThat(source(name + ".sql"))
                    .as("%s must include the shared candidate pipeline", name)
                    .contains("{{include:replenishment_candidates}}")
                    .doesNotContain("AS effective_reorder_point");
        }
    }

    @Test
    void everyConsumerRendersTheSameInventoryPreamble() throws IOException {
        String shared = compact(partial("latest_inventory"));

        for (String name : SHARED_PREAMBLE_CONSUMERS) {
            assertThat(preamble(CATALOG.sql(name), name, "demand AS ("))
                    .as("shared latest_inventory preamble in %s", name)
                    .isEqualTo(shared);
        }
        assertThat(preamble(CATALOG.sql("dashboard_summary"), "dashboard_summary", "inv AS ("))
                .as("shared latest_inventory preamble in dashboard_summary")
                .isEqualTo(shared);
    }

    @Test
    void proposalAndRunProjectionsShareOneColumnList() throws IOException {
        String proposalColumns = compact(partial("replenishment_proposal_columns"));
        for (String name : new String[]{"replenishment_proposals_list",
                "replenishment_proposals_for_po", "replenishment_proposal_find"}) {
            assertThat(selectList(CATALOG.sql(name)))
                    .as("%s proposal column list", name)
                    .isEqualTo(proposalColumns);
        }

        String runColumns = compact(partial("replenishment_run_columns"));
        for (String name : new String[]{"replenishment_run_find", "replenishment_runs_list"}) {
            assertThat(selectList(CATALOG.sql(name)))
                    .as("%s run column list", name)
                    .isEqualTo(runColumns);
        }
    }

    @Test
    void consumersReferenceThePartialsInsteadOfCopyingSql() throws IOException {
        for (String name : SHARED_FORMULA_CONSUMERS) {
            String source = source(name + ".sql");
            assertThat(source)
                    .as("%s must include the shared formula", name)
                    .contains("{{include:recommendation_formula}}")
                    .doesNotContain("targeted AS (", "cased AS (");
        }
        for (String name : ALL_PREAMBLE_CONSUMERS) {
            String source = source(name + ".sql");
            assertThat(source)
                    .as("%s must include the shared preamble", name)
                    .contains("{{include:latest_inventory}}")
                    .doesNotContain("latest_snapshot AS (", "latest_ledger AS (");
        }
        for (String name : SHARED_PREAMBLE_CONSUMERS) {
            assertThat(source(name + ".sql"))
                    .as("%s must include the shared demand window", name)
                    .contains("{{include:demand_window}}")
                    .doesNotContain("FROM `__PROJECT__.__DATASET__.sales`\n  WHERE sale_date BETWEEN");
        }
    }

    private static String formula(String sql, String name) {
        int start = sql.indexOf("targeted AS (");
        int finalStart = sql.indexOf("final AS (", start);
        int fromCased = sql.indexOf("FROM cased", finalStart);
        int end = sql.indexOf(")", fromCased) + 1;
        assertThat(start).as("targeted CTE in %s", name).isGreaterThanOrEqualTo(0);
        assertThat(finalStart).as("final CTE in %s", name).isGreaterThan(start);
        assertThat(fromCased).as("final CTE source in %s", name).isGreaterThan(finalStart);
        assertThat(end).as("final CTE close in %s", name).isGreaterThan(fromCased);
        return compact(sql.substring(start, end));
    }

    private static String preamble(String sql, String name, String endMarker) {
        int start = sql.indexOf("latest_snapshot AS (");
        int end = sql.indexOf(endMarker, start);
        assertThat(start).as("latest_snapshot CTE in %s", name).isGreaterThanOrEqualTo(0);
        assertThat(end).as("%s CTE in %s", endMarker, name).isGreaterThan(start);
        String region = sql.substring(start, end).stripTrailing();
        if (region.endsWith(",")) {
            region = region.substring(0, region.length() - 1);
        }
        return compact(region);
    }

    private static String between(String sql, String startMarker, String endMarker) {
        int start = sql.indexOf(startMarker);
        int end = sql.indexOf(endMarker, start);
        assertThat(start).as("%s in rendered SQL", startMarker).isGreaterThanOrEqualTo(0);
        assertThat(end).as("%s in rendered SQL", endMarker).isGreaterThan(start);
        return compact(sql.substring(start, end)).replaceAll(",$", "");
    }

    private static String selectList(String sql) {
        int select = sql.indexOf("SELECT");
        int from = sql.indexOf("FROM", select);
        assertThat(select).as("SELECT in query").isGreaterThanOrEqualTo(0);
        assertThat(from).as("FROM in query").isGreaterThan(select);
        return compact(sql.substring(select + "SELECT".length(), from));
    }

    private static String compact(String sql) {
        return sql.replaceAll("\\s+", "");
    }

    private static String partial(String name) throws IOException {
        // The rendered SQL has placeholders substituted; mirror that before comparing.
        return source("partials/" + name + ".sql")
                .replace("__PROJECT__", PROJECT)
                .replace("__DATASET__", DATASET);
    }

    private static String source(String relativePath) throws IOException {
        return new ClassPathResource("sql/retail/" + relativePath)
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
