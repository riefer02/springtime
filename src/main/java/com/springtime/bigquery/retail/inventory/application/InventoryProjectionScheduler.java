package com.springtime.bigquery.retail.inventory.application;

import com.springtime.bigquery.config.ProjectionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Optional scheduled refresh. Disabled by default because it writes to BigQuery and should be a
 * deliberate deployment decision (Cloud Run Job or a scheduler with a writer identity).
 */
@Component
@ConditionalOnProperty(name = "retail.projection.enabled", havingValue = "true")
public class InventoryProjectionScheduler {

    private static final Logger log = LoggerFactory.getLogger(InventoryProjectionScheduler.class);

    private final InventoryProjectionService projectionService;
    private final int lookbackDays;

    public InventoryProjectionScheduler(InventoryProjectionService projectionService,
                                        ProjectionProperties properties) {
        this.projectionService = projectionService;
        this.lookbackDays = properties.lookbackDays();
    }

    @Scheduled(fixedDelayString = "${retail.projection.interval-ms:900000}")
    public void refresh() {
        try {
            var result = projectionService.incremental(lookbackDays);
            log.info("inventory projection status={} from={} to={} affected={}",
                    result.status(), result.fromDate(), result.toDate(), result.affectedRows());
        } catch (RuntimeException exception) {
            log.error("inventory projection refresh failed", exception);
        }
    }
}
