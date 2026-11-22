package com.urbanmove.maintenance.application;

import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;

/** La orden pedida no existe. */
public final class OrderNotFoundException extends RuntimeException {
    private final MaintenanceOrderId orderId;

    public OrderNotFoundException(MaintenanceOrderId orderId) {
        super("Maintenance order not found: " + orderId);
        this.orderId = orderId;
    }

    public MaintenanceOrderId orderId() {
        return orderId;
    }
}
