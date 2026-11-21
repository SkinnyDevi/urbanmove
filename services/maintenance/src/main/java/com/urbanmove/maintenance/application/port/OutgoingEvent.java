package com.urbanmove.maintenance.application.port;

import com.urbanmove.maintenance.domain.event.DomainEvent;
import java.util.Objects;
import java.util.UUID;

/**
 * Evento de dominio listo para publicar: le añade el eventId (clave de idempotencia de los
 * consumidores) y el correlationId del flujo (AGENTS.md 3.2 y 6.1). El resto del envelope
 * (source, schemaVersion, formato JSON) lo pone la infraestructura.
 */
public record OutgoingEvent(UUID eventId, String correlationId, DomainEvent event) {

    public OutgoingEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(event, "event");
    }
}
