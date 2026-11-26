package com.urbanmove.maintenancedetection.application;

/** maintenance rechazó la orden por datos inválidos: reintentar no sirve. */
public final class OrderRejectedException extends RuntimeException {
    public OrderRejectedException(String message) {
        super(message);
    }
}
