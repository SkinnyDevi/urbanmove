package com.urbanmove.inventory.application;

import com.urbanmove.inventory.domain.VehicleId;

/** El vehículo pedido no existe en el inventario. */
public final class VehicleNotFoundException extends RuntimeException {
    public VehicleNotFoundException(VehicleId id) {
        super("Vehicle not found: " + id);
    }
}
