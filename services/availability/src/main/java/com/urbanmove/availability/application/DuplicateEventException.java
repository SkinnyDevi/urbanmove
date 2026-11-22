package com.urbanmove.availability.application;

import java.util.UUID;

/** Otro consumidor procesó este mismo evento justo antes (dos copias a la vez). */
public final class DuplicateEventException extends RuntimeException {
    public DuplicateEventException(UUID eventId) {
        super("Event already processed: " + eventId);
    }
}
