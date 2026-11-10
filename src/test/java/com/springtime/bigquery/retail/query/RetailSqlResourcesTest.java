package com.springtime.bigquery.retail.query;

import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.config.RetailProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** Guards the fixed SQL resources against accidental deletion or stale read models. */
class RetailSqlResourcesTest {

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

    @Test
    void ledgerMergeIsIdempotentAndParameterized() {
        assertThat(sql("inventory_movement_merge"))
                .contains("MERGE", "inventory_movements", "event_id", "PARSE_JSON");
        assertThat(sql("inventory_movement_merge")).doesNotContain("${");
    }

    @Test
    void readModelsUseTheLedgerProjection() {
        assertThat(sql("inventory_risk")).contains("inventory_on_hand_daily");
        assertThat(sql("replenishment")).contains("inventory_on_hand_daily");
        assertThat(sql("dashboard_summary")).contains("inventory_on_hand_daily");
        assertThat(sql("inventory_movements"))
                .contains("inventory_movements", "QUALIFY ROW_NUMBER()", "business_date BETWEEN");
        assertThat(sql("inventory_reconciliation"))
                .contains("FULL OUTER JOIN", "snapshot_date <= @as_of_date", "QUALIFY ROW_NUMBER()");
        assertThat(sql("latest_snapshot")).contains("latest_snapshot_date");
        assertThat(sql("latest_ledger")).contains("latest_ledger_date");
        assertThat(sql("inventory_projection_rebuild"))
                .contains("MERGE", "inventory_on_hand_projection", "WHEN NOT MATCHED BY SOURCE THEN", "QUALIFY ROW_NUMBER()");
        assertThat(sql("inventory_projection_incremental"))
                .contains("@from_date", "@to_date", "inventory_on_hand_projection", "QUALIFY ROW_NUMBER()");
        assertThat(sql("ledger_bounds")).contains("MIN(business_date)", "MAX(business_date)");
        assertThat(sql("projection_bounds")).contains("inventory_on_hand_projection");
        assertThat(sql("projection_unprojected_from"))
                .contains("ingested_at", "inventory_on_hand_projection");
        assertThat(sql("dead_letter_merge"))
                .contains("MERGE", "retail_event_dead_letters", "event_id");
        assertThat(sql("dead_letter_list")).contains("retail_event_dead_letters");
        assertThat(sql("dead_letter_mark_replayed")).contains("replay_status = 'REPLAYED'");
        assertThat(sql("replenishment_proposals_merge"))
                .contains("MERGE", "replenishment_proposals", "reason");
        assertThat(sql("replenishment_run_begin")).contains("replenishment_runs", "RUNNING");
        assertThat(sql("replenishment_run_complete")).contains("COMPLETED");
        assertThat(sql("replenishment_run_fail")).contains("FAILED");
        assertThat(sql("replenishment_run_summary")).contains("proposal_count", "total_order_value");
        assertThat(sql("replenishment_proposals_list")).contains("replenishment_proposals");
        assertThat(sql("replenishment_run_has_decisions")).contains("status != 'PROPOSED'");
        assertThat(sql("replenishment_proposal_decide"))
                .contains("BEGIN TRANSACTION", "ASSERT (@@row_count = 1)", "COMMIT TRANSACTION",
                        "replenishment_proposal_decisions", "run is not accepting decisions");
        assertThat(sql("replenishment_proposal_decisions_list"))
                .contains("replenishment_proposal_decisions");
        assertThat(sql("replenishment_proposals_for_po")).contains("status = 'APPROVED'");
    }

    @Test
    void sharedInventoryPreambleRendersInEveryConsumer() {
        for (String name : new String[]{"inventory_risk", "replenishment", "replenishment_proposals_merge"}) {
            assertThat(sql(name))
                    .as("shared latest_inventory preamble in %s", name)
                    .contains("latest_snapshot AS (", "latest_ledger AS (", "FULL OUTER JOIN latest_snapshot");
        }
        assertThat(sql("inventory_risk")).contains("inventory_on_hand_daily");
        assertThat(sql("replenishment")).contains("@target_cover_days", "recommended_units");
        assertThat(sql("replenishment_proposals_merge")).contains("@target_cover_days", "recommended_units");
    }

    @Test
    void ledgerDedupeUsesTheSameTieBreakInEveryQueryAndTheView() throws IOException {
        String expected = "ROW_NUMBER()OVER(PARTITIONBYevent_idORDERBYingested_at,event_time)";
        for (String name : new String[]{"inventory_movements", "inventory_projection_incremental",
                "inventory_projection_rebuild", "inventory_reconciliation"}) {
            assertThat(compact(sql(name)))
                    .as("%s must break dedupe ties the same way as the deduped view", name)
                    .contains(expected);
        }

        // The view is applied by `make retail-schema`, not loaded from the classpath.
        Path view = Path.of("db/retail/schema/002_inventory_views.sql");
        assertThat(view).as("dedupe view source").exists();
        assertThat(compact(Files.readString(view)))
                .as("the deduped view must use the same tie-break as the inline queries")
                .contains(expected);
    }

    @Test
    void everyRetailSqlResourceRendersWithAllIncludesResolved() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath:sql/retail/*.sql");
        assertThat(resources).isNotEmpty();

        for (Resource resource : resources) {
            String name = Objects.requireNonNull(resource.getFilename()).replace(".sql", "");
            assertThat(CATALOG.sql(name))
                    .as("%s must render without includes or placeholders", name)
                    .doesNotContain("{{", "__PROJECT__", "__DATASET__", "${");
        }
    }

    private static String compact(String sql) {
        return sql.replaceAll("\\s+", "");
    }

    private static String sql(String name) {
        return CATALOG.sql(name);
    }
}
