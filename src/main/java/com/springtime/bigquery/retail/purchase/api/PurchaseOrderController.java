package com.springtime.bigquery.retail.purchase.api;

import com.springtime.bigquery.model.query.PageResponse;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderCancelRequest;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderEventView;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderLineView;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderRequest;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderSubmitRequest;
import com.springtime.bigquery.retail.purchase.dto.PurchaseOrderView;
import com.springtime.bigquery.retail.purchase.application.PurchaseOrderService;
import com.springtime.bigquery.retail.purchase.model.PurchaseOrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Retail Purchase Orders", description = "Purchase orders created from approved proposals")
@RestController
@RequestMapping(value = "/api/v1/retail/purchase-orders", produces = MediaType.APPLICATION_JSON_VALUE)
public class PurchaseOrderController {

    private final PurchaseOrderService orderService;

    public PurchaseOrderController(PurchaseOrderService orderService) {
        this.orderService = orderService;
    }

    @Operation(operationId = "createPurchaseOrder",
            summary = "Create a draft purchase order from approved proposals")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public PurchaseOrderView create(@Valid @RequestBody PurchaseOrderRequest request,
                                    Authentication authentication) {
        return orderService.create(request, actor(authentication));
    }

    @Operation(operationId = "listPurchaseOrders",
            summary = "List purchase orders, one page at a time")
    @GetMapping
    public PageResponse<PurchaseOrderView> list(
            @Parameter(description = "Optional store id") @RequestParam(required = false) String storeId,
            @Parameter(description = "Optional supplier id") @RequestParam(required = false) String supplierId,
            @Parameter(description = "Optional status") @RequestParam(required = false) PurchaseOrderStatus status,
            @Parameter(description = "Opaque token from a previous response")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "Rows per page; clamped to the server ceiling")
            @RequestParam(defaultValue = "${retail.default-page-size:50}") int limit) {
        return orderService.list(storeId, supplierId, status, pageToken, limit);
    }

    @Operation(operationId = "getPurchaseOrder", summary = "Get one purchase order")
    @GetMapping("/{poId}")
    public PurchaseOrderView get(@PathVariable String poId) {
        return orderService.get(poId);
    }

    @Operation(operationId = "listPurchaseOrderLines",
            summary = "List purchase order lines, one page at a time")
    @GetMapping("/{poId}/lines")
    public PageResponse<PurchaseOrderLineView> lines(
            @PathVariable String poId,
            @Parameter(description = "Opaque token from a previous response")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "Rows per page; clamped to the server ceiling")
            @RequestParam(defaultValue = "200") int limit) {
        return orderService.lines(poId, pageToken, limit);
    }

    @Operation(operationId = "submitPurchaseOrder", summary = "Submit a draft purchase order")
    @PostMapping("/{poId}/submit")
    public PurchaseOrderView submit(@PathVariable String poId,
                                    @Valid @RequestBody PurchaseOrderSubmitRequest request,
                                    Authentication authentication) {
        return orderService.submit(poId, request.expectedVersion(),
                request.supplierReference(), request.note(), actor(authentication));
    }

    @Operation(operationId = "cancelPurchaseOrder", summary = "Cancel a draft purchase order")
    @PostMapping("/{poId}/cancel")
    public PurchaseOrderView cancel(@PathVariable String poId,
                                    @Valid @RequestBody PurchaseOrderCancelRequest request,
                                    Authentication authentication) {
        return orderService.cancel(poId, request.expectedVersion(), request.note(), actor(authentication));
    }

    @Operation(operationId = "listPurchaseOrderEvents",
            summary = "Purchase order lifecycle audit, one page at a time")
    @GetMapping("/{poId}/history")
    public PageResponse<PurchaseOrderEventView> history(
            @PathVariable String poId,
            @Parameter(description = "Opaque token from a previous response")
            @RequestParam(required = false) String pageToken,
            @Parameter(description = "Rows per page; clamped to the server ceiling")
            @RequestParam(defaultValue = "${retail.default-page-size:50}") int limit) {
        return orderService.history(poId, pageToken, limit);
    }

    private static String actor(Authentication authentication) {
        return authentication == null ? "-" : authentication.getName();
    }
}
