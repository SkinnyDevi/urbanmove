package com.urbanmove.inventory.application;

import com.urbanmove.inventory.domain.VehicleId;

/** Llega un evento de un vehículo que inventory aún no conoce; se reintenta. */
public final class UnknownVehicleException extends RuntimeException {
    public UnknownVehicleException(VehicleId id) {
        super("Unknown vehicle: " + id);
    }
}
