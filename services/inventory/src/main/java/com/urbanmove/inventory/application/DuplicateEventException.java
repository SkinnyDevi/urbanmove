package com.urbanmove.inventory.application;

/** Otro consumidor procesó el mismo evento justo antes. */
public final class DuplicateEventException extends RuntimeException {
    public DuplicateEventException(java.util.UUID eventId) {
        super("Event already processed: " + eventId);
    }
}
