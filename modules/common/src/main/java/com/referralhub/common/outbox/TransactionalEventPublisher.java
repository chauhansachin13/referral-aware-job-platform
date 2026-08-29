package com.referralhub.common.outbox;

import com.referralhub.common.events.DomainEvent;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only way a module is allowed to emit an event.
 *
 * <p>{@code MANDATORY} propagation is deliberate: calling this outside a transaction throws
 * instead of silently writing a row that could be published without its business change ever
 * having committed. Getting a loud failure in a test is much cheaper than a phantom event in
 * production.
 */
@Component
public class TransactionalEventPublisher {

    private final OutboxStore store;

    public TransactionalEventPublisher(OutboxStore store) {
        this.store = store;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(DomainEvent event) {
        return store.append(event);
    }
}
