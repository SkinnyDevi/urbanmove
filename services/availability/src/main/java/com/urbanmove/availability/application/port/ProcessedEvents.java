package com.urbanmove.availability.application.port;

import java.util.UUID;

/** Eventos ya procesados, por eventId. */
public interface ProcessedEvents {
    boolean isProcessed(UUID eventId);
}
