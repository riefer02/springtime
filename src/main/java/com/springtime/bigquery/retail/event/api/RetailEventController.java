package com.springtime.bigquery.retail.event.api;

import com.springtime.bigquery.retail.event.dto.AcceptedEvent;
import com.springtime.bigquery.retail.event.dto.RetailEventRequest;
import com.springtime.bigquery.retail.event.application.RetailEventPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Retail", description = "Generic retail inventory, replenishment, and dashboard analytics")
@RestController
@RequestMapping(value = "/api/v1/retail", produces = MediaType.APPLICATION_JSON_VALUE)
public class RetailEventController {

    private final RetailEventPublisher publisher;

    public RetailEventController(RetailEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Operation(operationId = "publishRetailEvent",
            summary = "Accept a retail event for asynchronous processing")
    @PostMapping(value = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public AcceptedEvent publish(@Valid @RequestBody RetailEventRequest request) {
        return publisher.publish(request);
    }
}
