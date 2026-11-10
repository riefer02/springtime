package com.springtime.bigquery.control.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the durable supplier outbox. {@code worker-enabled=false} stops polling; {@code enabled=false}
 * removes the worker bean entirely.
 */
@Component
@ConditionalOnProperty(name = "control.supplier.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class SupplierTransmissionWorker {

    private static final Logger log = LoggerFactory.getLogger(SupplierTransmissionWorker.class);

    private final SupplierTransmissionService service;
    private final SupplierOutboxProperties properties;

    public SupplierTransmissionWorker(SupplierTransmissionService service,
                                      SupplierOutboxProperties properties) {
        this.service = service;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${control.supplier.outbox.poll-interval-ms:5000}")
    public void poll() {
        if (!properties.workerEnabled()) {
            return;
        }
        try {
            service.processDue();
        } catch (RuntimeException exception) {
            log.error("supplier transmission worker failed", exception);
        }
    }
}
