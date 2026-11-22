package com.urbanmove.availability.application;

import com.urbanmove.availability.domain.VehicleId;

/** GET de un vehículo que no existe. */
public final class VehicleNotFoundException extends RuntimeException {
    public VehicleNotFoundException(VehicleId vehicleId) {
        super("Vehicle not found: " + vehicleId);
    }
}
