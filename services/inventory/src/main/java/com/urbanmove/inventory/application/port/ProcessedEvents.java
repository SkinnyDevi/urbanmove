package com.urbanmove.inventory.application.port;

import java.util.UUID;

/** Eventos del bus ya procesados, por eventId. */
public interface ProcessedEvents {
    boolean isProcessed(UUID eventId);
}
