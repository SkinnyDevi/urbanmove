package com.urbanmove.availability.application;

import com.urbanmove.availability.domain.VehicleId;

/** El evento se refiere a un vehículo que availability aún no conoce. */
public final class UnknownVehicleException extends RuntimeException {
    public UnknownVehicleException(VehicleId vehicleId) {
        super("Unknown vehicle: " + vehicleId);
    }
}
