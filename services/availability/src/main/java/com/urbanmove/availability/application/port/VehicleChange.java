package com.urbanmove.availability.application.port;

import com.urbanmove.availability.domain.VehicleAvailability;
import java.util.Objects;

/** Un vehículo modificado, con la versión que tenía al leerlo. */
public record VehicleChange(VehicleAvailability vehicle, Long expectedVersion) {
    public VehicleChange {
        Objects.requireNonNull(vehicle, "vehicle");
    }

    public static VehicleChange created(VehicleAvailability vehicle) {
        return new VehicleChange(vehicle, null);
    }

    public static VehicleChange updated(VehicleAvailability vehicle) {
        return new VehicleChange(vehicle, vehicle.version());
    }
}
