package com.referralhub.common.events;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * A request to notify a human. Nothing in this codebase sends mail: the platform emits the
 * intent and an external notifier owns delivery, so the transactional path never blocks on
 * an SMTP or push provider.
 */
public record NotificationRequested(
        UUID notificationId,
        UUID recipientId,
        String channel,
        String template,
        Map<String, String> params,
        Instant requestedAt) implements DomainEvent {

    @Override
    public String eventType() {
        return "notification.requested";
    }

    @Override
    public String aggregateType() {
        return "notification";
    }

    @Override
    public UUID aggregateId() {
        return notificationId;
    }

    @Override
    public String topic() {
        return Topics.NOTIFICATIONS;
    }
}
