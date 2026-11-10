package com.springtime.bigquery.messaging;

import java.util.Map;

public interface MessagePublisher {

    String publish(String topic, MessagePayload payload);

    /**
     * Publish with transport attributes (for example W3C trace context). Adapters that cannot
     * carry attributes keep the original two-argument behaviour.
     */
    default String publish(String topic, MessagePayload payload, Map<String, String> attributes) {
        return publish(topic, payload);
    }
}
