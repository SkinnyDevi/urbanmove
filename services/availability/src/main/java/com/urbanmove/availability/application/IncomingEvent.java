package com.urbanmove.availability.application;

import java.util.Objects;

/** Evento recibido de la cola: metadatos y hecho que representa. */
public record IncomingEvent(EventMetadata metadata, VehicleEvent event) {
    public IncomingEvent {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(event, "event");
    }
}
