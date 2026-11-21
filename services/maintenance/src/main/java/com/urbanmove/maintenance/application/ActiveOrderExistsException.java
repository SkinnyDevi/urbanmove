package com.urbanmove.maintenance.application;

import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.Identifiers.VehicleId;

/** El vehículo ya tiene una orden activa (pendiente o en el taller): no se abre otra. 409. */
public final class ActiveOrderExistsException extends RuntimeException {

    private final VehicleId vehicleId;
    private final MaintenanceOrderId existingOrderId;

    public ActiveOrderExistsException(VehicleId vehicleId, MaintenanceOrderId existingOrderId) {
        super("Vehicle " + vehicleId + " already has an active maintenance order: " + existingOrderId);
        this.vehicleId = vehicleId;
        this.existingOrderId = existingOrderId;
    }

    public VehicleId vehicleId() {
        return vehicleId;
    }

    /** Puede ser null si la orden activa no se pudo leer. */
    public MaintenanceOrderId existingOrderId() {
        return existingOrderId;
    }
}
