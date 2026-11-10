package com.springtime.bigquery.retail.query;

import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.config.RetailProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Include expansion, caching, and fail-fast behavior of {@link RetailSqlCatalog}. */
class RetailSqlCatalogTest {

    private static final String MAIN_PREFIX = "classpath:sql/retail/";
    private static final String PARTIAL_PREFIX = MAIN_PREFIX + "partials/";

    private static final RetailProperties RETAIL = new RetailProperties(
            "retail_ds", 50, 200, 28, 14, 1_000L, "USD",
            Duration.ofMinutes(5), Duration.ofDays(365), 0, ZoneOffset.UTC);

    private static final BigQueryProperties BIGQUERY = new BigQueryProperties(
            "project-x", "US", "", 1_000, 100, true, true, 2,
            Duration.ofSeconds(1), Duration.ofSeconds(10));

    @Test
    void expandsIncludesAndSubstitutesPlaceholders() {
        RetailSqlCatalog catalog = catalog(Map.of(
                "demo.sql", "WITH {{include:shared}},\nSELECT 1 FROM `__PROJECT__.__DATASET__.t`;",
                "partials/shared.sql", "shared AS (SELECT 1 AS one)"));

        assertThat(catalog.sql("demo"))
                .isEqualTo("WITH shared AS (SELECT 1 AS one),\n"
                        + "SELECT 1 FROM `project-x.retail_ds.t`;");
    }

    @Test
    void trimsThePartialTrailingNewlineSoCommasStayAttached() {
        RetailSqlCatalog catalog = catalog(Map.of(
                "commas.sql", "WITH {{include:first}},\nsecond AS (SELECT 1)",
                "partials/first.sql", "first AS (SELECT 1)\n"));

        assertThat(catalog.sql("commas")).isEqualTo("WITH first AS (SELECT 1),\nsecond AS (SELECT 1)");
    }

    @Test
    void expandsTwoLevelsOfNesting() {
        RetailSqlCatalog catalog = catalog(Map.of(
                "nested.sql", "WITH {{include:outer}} SELECT * FROM x;",
                "partials/outer.sql", "{{include:inner}}\nouter AS (SELECT 1)",
                "partials/inner.sql", "inner AS (SELECT 1)"));

        assertThat(catalog.sql("nested"))
                .isEqualTo("WITH inner AS (SELECT 1)\nouter AS (SELECT 1) SELECT * FROM x;");
    }

    @Test
    void cachesTheRenderedSql() {
        Map<String, String> resources = Map.of("cached.sql", "SELECT {{include:shared}};",
                "partials/shared.sql", "1");
        AtomicInteger loads = new AtomicInteger();
        RetailSqlCatalog catalog = catalog(resources, loads);

        String first = catalog.sql("cached");
        int loadsAfterFirstRender = loads.get();
        String second = catalog.sql("cached");

        assertThat(second).isSameAs(first);
        assertThat(loads.get()).isEqualTo(loadsAfterFirstRender);
    }

    @Test
    void rejectsAnInvalidPartialName() {
        RetailSqlCatalog catalog = catalog(Map.of(
                "invalid_include.sql", "WITH {{include:Not-A-Partial}} SELECT 1;"));

        assertThatThrownBy(() -> catalog.sql("invalid_include"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid retail SQL include name")
                .hasMessageContaining("Not-A-Partial");
    }

    @Test
    void failsOnAMissingPartial() {
        RetailSqlCatalog catalog = catalog(Map.of(
                "missing_include.sql", "WITH {{include:absent_partial}} SELECT 1;"));

        assertThatThrownBy(() -> catalog.sql("missing_include"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing retail SQL partial")
                .hasMessageContaining("absent_partial")
                .hasMessageContaining("missing_include");
    }

    @Test
    void failsOnAnUnclosedInclude() {
        RetailSqlCatalog catalog = catalog(Map.of(
                "unclosed_include.sql", "WITH {{include:shared SELECT 1;"));

        assertThatThrownBy(() -> catalog.sql("unclosed_include"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unresolved retail SQL include");
    }

    @Test
    void failsOnCyclicIncludes() {
        RetailSqlCatalog catalog = catalog(Map.of(
                "cyclic.sql", "WITH {{include:self_loop}} SELECT 1;",
                "partials/self_loop.sql", "x AS ({{include:self_loop}})"));

        assertThatThrownBy(() -> catalog.sql("cyclic"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cyclic retail SQL include")
                .hasMessageContaining("self_loop");
    }

    @Test
    void failsBeyondTheMaximumIncludeDepth() {
        RetailSqlCatalog catalog = catalog(Map.of(
                "too_deep.sql", "WITH {{include:level_one}} SELECT 1;",
                "partials/level_one.sql", "{{include:level_two}}",
                "partials/level_two.sql", "{{include:level_three}}",
                "partials/level_three.sql", "SELECT 1"));

        assertThatThrownBy(() -> catalog.sql("too_deep"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nesting exceeds 2");
    }

    @Test
    void rejectsAnUnsafeMainResourceName() {
        assertThatThrownBy(() -> catalog(Map.of()).sql("../secrets"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RetailSqlCatalog catalog(Map<String, String> resources) {
        return catalog(resources, new AtomicInteger());
    }

    /** Builds a catalog whose classpath is an in-memory map keyed by path under {@code sql/retail/}. */
    private static RetailSqlCatalog catalog(Map<String, String> resources, AtomicInteger loads) {
        Map<String, String> contents = new HashMap<>(resources);
        ResourceLoader loader = new ResourceLoader() {
            @Override
            public Resource getResource(String location) {
                loads.incrementAndGet();
                String key = location.replace(MAIN_PREFIX, "");
                String sql = contents.get(key);
                return sql == null
                        ? new ClassPathResource("sql/retail/" + key + ".missing")
                        : new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8));
            }

            @Override
            public ClassLoader getClassLoader() {
                return RetailSqlCatalogTest.class.getClassLoader();
            }
        };
        return new RetailSqlCatalog(loader, BIGQUERY, RETAIL);
    }
}
