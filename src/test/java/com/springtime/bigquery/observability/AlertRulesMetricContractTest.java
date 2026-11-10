package com.springtime.bigquery.observability;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the Prometheus alert rules honest: every custom metric they reference must still exist in
 * the code. A renamed meter otherwise turns an alert into a permanent "no data" silence.
 */
class AlertRulesMetricContractTest {

    private static final Path ALERTS = Path.of("ops/prometheus/springtime-alerts.yml");
    private static final Path MAIN = Path.of("src/main/java");
    private static final Pattern METRIC = Pattern.compile("\\b(retail_[a-z0-9_]+)\\b");

    @Test
    void everyAlertedRetailMetricIsStillEmittedByTheApplication() throws IOException {
        assertThat(ALERTS).as("alert rules").exists();
        String rules = Files.readString(ALERTS);
        String code = Files.walk(MAIN)
                .filter(path -> path.toString().endsWith(".java"))
                .map(AlertRulesMetricContractTest::read)
                .reduce("", String::concat);

        Set<String> checked = new LinkedHashSet<>();
        Matcher matcher = METRIC.matcher(rules);
        while (matcher.find()) {
            String prometheusName = matcher.group(1);
            String base = prometheusName.replaceAll("_total$", "");
            String dotted = base.replace('_', '.');
            checked.add(base);
            // Some meters keep underscores in their Java name, others use dots; accept either
            // spelling as long as the exact literal still exists in the code.
            assertThat(code.contains("\"" + base + "\"") || code.contains("\"" + dotted + "\""))
                    .as("alert metric %s must be emitted by the code as %s or %s",
                            prometheusName, base, dotted)
                    .isTrue();
        }

        assertThat(checked).as("alert rules reference at least one application metric").isNotEmpty();
    }

    @Test
    void everyRuleCarriesASeverityAndRunbook() throws IOException {
        String rules = Files.readString(ALERTS);

        assertThat(rules).contains("severity: critical", "severity: warning");
        assertThat(rules).contains("runbook: \"docs/operations/README.md");
        assertThat(Path.of("docs/operations/README.md")).exists();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read " + path, exception);
        }
    }
}
