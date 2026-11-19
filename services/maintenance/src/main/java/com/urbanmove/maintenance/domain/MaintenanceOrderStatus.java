package com.urbanmove.maintenance.domain;

/**
 * Estados de la orden de mantenimiento.
 *
 * <p>WITHDRAWN, CHARGING, REPAIRING y READY_FOR_SERVICE coinciden con los estados oficiales del
 * vehículo (AGENTS.md 3.4): mientras la orden está en uno de ellos, ese es el estado real del
 * vehículo dentro del taller. PENDING (orden creada, vehículo aún en la calle), RETURNED y
 * CANCELLED son estados propios de la orden. Ver docs/architecture/vehicle-state-machine.md.
 */
public enum MaintenanceOrderStatus {
    PENDING,
    WITHDRAWN,
    CHARGING,
    REPAIRING,
    READY_FOR_SERVICE,
    RETURNED,
    CANCELLED
}
