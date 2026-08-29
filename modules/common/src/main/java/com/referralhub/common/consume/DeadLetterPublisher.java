package com.referralhub.common.consume;

import com.referralhub.common.events.Topics;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Parks a record the consumer cannot make progress on.
 *
 * <p>The original payload is forwarded untouched and the failure context travels in headers, so
 * a replay tool can re-drive the DLQ into the source topic after a fix without having to unwrap
 * some bespoke envelope.
 */
@Component
public class DeadLetterPublisher {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPublisher.class);

    private final KafkaTemplate<String, String> kafka;

    public DeadLetterPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    public void publish(String sourceTopic, String key, String payload, Throwable cause) {
        String dlq = Topics.dlqFor(sourceTopic);
        ProducerRecord<String, String> record = new ProducerRecord<>(dlq, key, payload);
        record.headers().add("x-original-topic", sourceTopic.getBytes(StandardCharsets.UTF_8));
        record.headers().add("x-exception-class",
                cause.getClass().getName().getBytes(StandardCharsets.UTF_8));
        record.headers().add("x-exception-message",
                String.valueOf(cause.getMessage()).getBytes(StandardCharsets.UTF_8));
        record.headers().add("x-failed-at",
                java.time.Instant.now().toString().getBytes(StandardCharsets.UTF_8));
        kafka.send(record);
        log.error("Sent record with key {} from {} to {}", key, sourceTopic, dlq, cause);
    }
}
