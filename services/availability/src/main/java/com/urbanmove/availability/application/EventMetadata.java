package com.urbanmove.availability.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Datos del envelope que importan al consumidor. */
public record EventMetadata(UUID eventId, String eventType, String correlationId, Instant occurredAt) {
    public EventMetadata {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
