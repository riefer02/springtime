package com.springtime.bigquery.retail.query;

import com.springtime.bigquery.config.BigQueryProperties;
import com.springtime.bigquery.config.RetailProperties;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single cache and substitution point for owned retail SQL. Resource names are restricted to a
 * fixed alphabet; project and dataset are validated configuration, never request input.
 *
 * <p>Main resources may pull in shared fragments with {@code {{include:partial_name}}}, resolved
 * from {@code sql/retail/partials/<partial_name>.sql}. Includes are limited to
 * {@value #MAX_INCLUDE_DEPTH} levels of nesting, may not repeat a partial already being expanded,
 * and any include left unresolved fails fast while rendering instead of reaching BigQuery. A
 * single trailing newline on a partial is trimmed so a comma written after the include stays on
 * the fragment's last line.
 */
@Component
public class RetailSqlCatalog {

    private static final Pattern NAME = Pattern.compile("[a-z0-9_]+");
    private static final Pattern INCLUDE = Pattern.compile("\\{\\{include:([^}]*)\\}\\}");
    private static final String MAIN_PREFIX = "sql/retail/";
    private static final String PARTIAL_PREFIX = "sql/retail/partials/";
    private static final int MAX_INCLUDE_DEPTH = 2;

    private final ResourceLoader resourceLoader;
    private final BigQueryProperties bigQueryProperties;
    private final RetailProperties retailProperties;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public RetailSqlCatalog(ResourceLoader resourceLoader,
                            BigQueryProperties bigQueryProperties,
                            RetailProperties retailProperties) {
        this.resourceLoader = resourceLoader;
        this.bigQueryProperties = bigQueryProperties;
        this.retailProperties = retailProperties;
    }

    public String sql(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid retail SQL resource name");
        }
        return cache.computeIfAbsent(name, this::render);
    }

    private String render(String name) {
        String source = read(MAIN_PREFIX + name + ".sql", "retail SQL " + name);
        return substitute(expand(source, "retail SQL " + name, 0, new ArrayDeque<>()));
    }

    private String expand(String sql, String origin, int depth, Deque<String> expanding) {
        Matcher matcher = INCLUDE.matcher(sql);
        StringBuilder rendered = new StringBuilder(sql.length());
        int cursor = 0;
        while (matcher.find()) {
            String partial = matcher.group(1);
            if (!NAME.matcher(partial).matches()) {
                throw new IllegalStateException(
                        "Invalid retail SQL include name '" + partial + "' in " + origin);
            }
            if (depth >= MAX_INCLUDE_DEPTH) {
                throw new IllegalStateException("Retail SQL include nesting exceeds "
                        + MAX_INCLUDE_DEPTH + " in " + origin + " (include: " + partial + ")");
            }
            if (expanding.contains(partial)) {
                throw new IllegalStateException("Cyclic retail SQL include '" + partial
                        + "' in " + origin + " (chain: " + String.join(" -> ", expanding) + ")");
            }
            rendered.append(sql, cursor, matcher.start());
            cursor = matcher.end();
            expanding.push(partial);
            rendered.append(expand(
                    readPartial(partial, origin), "retail SQL partial " + partial, depth + 1, expanding));
            expanding.pop();
        }
        rendered.append(sql, cursor, sql.length());
        String expanded = rendered.toString();
        if (expanded.contains("{{")) {
            throw new IllegalStateException("Unresolved retail SQL include in " + origin);
        }
        return expanded;
    }

    private String readPartial(String partial, String origin) {
        String location = PARTIAL_PREFIX + partial + ".sql";
        Resource resource = resourceLoader.getResource("classpath:" + location);
        if (!resource.exists()) {
            throw new IllegalStateException(
                    "Missing retail SQL partial " + location + " (included by " + origin + ")");
        }
        return trimTrailingNewline(read(resource, location));
    }

    /** Keeps commas that follow an include attached to the fragment's last line. */
    private static String trimTrailingNewline(String partial) {
        if (partial.endsWith("\r\n")) {
            return partial.substring(0, partial.length() - 2);
        }
        if (partial.endsWith("\n")) {
            return partial.substring(0, partial.length() - 1);
        }
        return partial;
    }

    private String read(String location, String description) {
        return read(resourceLoader.getResource("classpath:" + location), description);
    }

    private String read(Resource resource, String description) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to load " + description, exception);
        }
    }

    private String substitute(String sql) {
        return sql.replace("__PROJECT__", bigQueryProperties.projectId())
                .replace("__DATASET__", retailProperties.dataset());
    }
}
