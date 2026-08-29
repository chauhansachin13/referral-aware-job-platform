package com.referralhub.common.consume;

import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Wraps a message handler so that redelivery is free.
 *
 * <p>The claim and the handler share one transaction. If the handler throws, the claim rolls
 * back with it and the message is genuinely retried rather than being marked done for a unit of
 * work that never happened.
 */
@Component
public class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final ProcessedMessageStore store;

    public IdempotentConsumer(ProcessedMessageStore store) {
        this.store = store;
    }

    /**
     * @param messageKey a stable identity for the message — the producing event's id, never the
     *                   Kafka offset, which changes on replay.
     * @return {@code true} if the handler ran, {@code false} if this was a duplicate.
     */
    @Transactional
    public <T> boolean handle(String consumerGroup, String messageKey, T message, Consumer<T> handler) {
        if (!store.claim(consumerGroup, messageKey)) {
            log.debug("Skipping duplicate message {} for group {}", messageKey, consumerGroup);
            return false;
        }
        handler.accept(message);
        return true;
    }
}
