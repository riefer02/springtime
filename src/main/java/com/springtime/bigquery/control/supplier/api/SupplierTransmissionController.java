package com.springtime.bigquery.control.supplier.api;

import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.control.supplier.SupplierTransmissionService;
import com.springtime.bigquery.control.supplier.SupplierTransmissionStatus;
import com.springtime.bigquery.control.supplier.dto.SupplierAckRequest;
import com.springtime.bigquery.control.supplier.dto.SupplierTransmissionView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Retail Supplier Transmissions", description = "Durable supplier outbox and acknowledgement")
@RestController
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
public class SupplierTransmissionController {

    private final SupplierTransmissionService service;

    public SupplierTransmissionController(SupplierTransmissionService service) {
        this.service = service;
    }

    @Operation(operationId = "enqueuePurchaseOrderTransmission",
            summary = "Enqueue a submitted purchase order in the supplier outbox")
    @PostMapping("/api/v1/retail/purchase-orders/{poId}/transmit")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SupplierTransmissionView enqueue(@PathVariable String poId,
                                            Authentication authentication) {
        return service.enqueueForPo(poId, actor(authentication));
    }

    @Operation(operationId = "listSupplierTransmissions",
            summary = "List supplier transmissions, one page at a time")
    @GetMapping("/api/v1/retail/supplier-transmissions")
    public PageResponse<SupplierTransmissionView> list(
            @Parameter(description = "Optional status") @RequestParam(required = false)
            SupplierTransmissionStatus status,
            @Parameter(description = "Opaque token from a previous response")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "Rows per page; clamped to the server ceiling")
            @RequestParam(defaultValue = "${retail.default-page-size:50}") int limit) {
        return service.list(status, pageToken, limit);
    }

    @Operation(operationId = "getSupplierTransmission", summary = "Get one supplier transmission")
    @GetMapping("/api/v1/retail/supplier-transmissions/{transmissionId}")
    public SupplierTransmissionView get(@PathVariable UUID transmissionId) {
        return service.get(transmissionId);
    }

    @Operation(operationId = "retrySupplierTransmission", summary = "Retry a FAILED transmission")
    @PostMapping("/api/v1/retail/supplier-transmissions/{transmissionId}/retry")
    public SupplierTransmissionView retry(@PathVariable UUID transmissionId,
                                          Authentication authentication) {
        return service.retry(transmissionId, actor(authentication));
    }

    @Operation(operationId = "acknowledgeSupplierTransmission",
            summary = "Mark a SENT transmission acknowledged")
    @PostMapping("/api/v1/retail/supplier-transmissions/{transmissionId}/ack")
    public SupplierTransmissionView acknowledge(@PathVariable UUID transmissionId,
                                                 @Valid @RequestBody(required = false)
                                                 SupplierAckRequest request,
                                                 Authentication authentication) {
        return service.acknowledge(transmissionId,
                request == null ? null : request.supplierReference(), actor(authentication));
    }

    private static String actor(Authentication authentication) {
        return authentication == null ? "-" : authentication.getName();
    }
}
