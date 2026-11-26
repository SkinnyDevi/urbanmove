package com.urbanmove.maintenancedetection.application;

import java.util.Objects;
import java.util.UUID;

/** Datos del envelope del evento que importan a la Lambda. */
public record SignalMetadata(UUID eventId, String eventType, String correlationId) {
    public SignalMetadata {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(correlationId, "correlationId");
    }
}
