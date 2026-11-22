package com.urbanmove.maintenance.application;

import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;

/** Otra petición modificó la orden entre la lectura y la escritura (bloqueo optimista). */
public final class ConcurrentUpdateException extends RuntimeException {
    public ConcurrentUpdateException(MaintenanceOrderId orderId) {
        super("Maintenance order " + orderId + " was modified by another request; read it again and retry");
    }
}
