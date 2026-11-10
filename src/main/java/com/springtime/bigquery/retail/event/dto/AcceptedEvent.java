package com.springtime.bigquery.retail.event.dto;

public record AcceptedEvent(String eventId, String messageId, String topic, String status) {

    public static AcceptedEvent accepted(String eventId, String messageId, String topic) {
        return new AcceptedEvent(eventId, messageId, topic, "ACCEPTED");
    }
}
