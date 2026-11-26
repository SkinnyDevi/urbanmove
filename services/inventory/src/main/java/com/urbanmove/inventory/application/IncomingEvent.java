package com.urbanmove.inventory.application;

import java.util.Objects;

/** Evento recibido del bus: su envelope y el hecho que contiene. */
public record IncomingEvent(EventMetadata metadata, InventoryEvent event) {
    public IncomingEvent {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(event);
    }
}
