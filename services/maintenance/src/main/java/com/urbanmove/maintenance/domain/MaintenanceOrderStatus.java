package com.urbanmove.maintenance.domain;

/** Estados de la orden de mantenimiento. */
public enum MaintenanceOrderStatus {
    PENDING,
    WITHDRAWN,
    CHARGING,
    REPAIRING,
    READY_FOR_SERVICE,
    RETURNED,
    CANCELLED
}
