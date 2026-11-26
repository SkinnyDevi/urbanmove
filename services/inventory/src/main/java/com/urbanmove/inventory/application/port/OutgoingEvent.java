package com.urbanmove.inventory.application.port;

import com.urbanmove.inventory.domain.event.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/** Evento de dominio con su eventId y correlationId, listo para publicar. */
public record OutgoingEvent(UUID eventId, String correlationId, DomainEvent event) {
    public OutgoingEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(event, "event");
    }
}
