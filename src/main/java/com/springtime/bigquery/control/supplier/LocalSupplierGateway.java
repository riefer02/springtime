package com.springtime.bigquery.control.supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * Local supplier adapter for demos/tests. It intentionally does not pretend to be an EDI/API
 * integration; replace it with a supplier-specific adapter for production.
 */
@Component
@ConditionalOnProperty(name = "control.supplier.gateway", havingValue = "local")
public class LocalSupplierGateway implements SupplierGateway {

    private static final Logger log = LoggerFactory.getLogger(LocalSupplierGateway.class);

    private final Clock clock;

    public LocalSupplierGateway(Clock clock) {
        this.clock = clock;
    }

    @Override
    public SupplierSubmission submit(SupplierTransmission transmission) {
        log.info("local supplier adapter accepted poId={} transmissionId={}",
                transmission.poId(), transmission.transmissionId());
        return new SupplierSubmission("local-" + transmission.poId(), clock.instant());
    }
}
