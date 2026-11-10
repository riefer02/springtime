package com.springtime.bigquery.retail.event.application;

import com.springtime.bigquery.retail.event.dto.EventEnvelope;
import com.springtime.bigquery.messaging.MessagePublisher;
import com.springtime.bigquery.messaging.MessagingProperties;
import com.springtime.bigquery.retail.event.dto.AcceptedEvent;
import com.springtime.bigquery.retail.event.dto.RetailEventRequest;
import org.springframework.stereotype.Service;

/** Validates and publishes a retail event. Processing is deliberately not on the request path. */
@Service
public class RetailEventPublisher {

    private final MessagePublisher publisher;
    private final MessagingProperties properties;
    private final RetailEventTranslator translator;

    public RetailEventPublisher(MessagePublisher publisher,
                                MessagingProperties properties,
                                RetailEventTranslator translator) {
        this.publisher = publisher;
        this.properties = properties;
        this.translator = translator;
    }

    public AcceptedEvent publish(RetailEventRequest request) {
        translator.validateForSubmission(request);
        EventEnvelope event = new EventEnvelope(
                request.eventId(),
                request.eventType().name(),
                request.occurredAt(),
                RetailEventTranslator.SUPPORTED_SCHEMA_VERSION,
                request.storeId(),
                request.productId(),
                request.quantity(),
                request.payload());
        String messageId = publisher.publish(properties.eventsTopic(), event);
        return AcceptedEvent.accepted(event.eventId(), messageId, properties.eventsTopic());
    }
}
