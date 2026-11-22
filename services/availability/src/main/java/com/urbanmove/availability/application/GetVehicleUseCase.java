package com.urbanmove.availability.application;

import com.urbanmove.availability.application.port.VehicleReader;
import com.urbanmove.availability.domain.VehicleAvailability;
import com.urbanmove.availability.domain.VehicleId;
import java.util.Objects;

/** Caso de uso: estado operativo actual de un vehículo. */
public final class GetVehicleUseCase {
    private final VehicleReader reader;

    public GetVehicleUseCase(VehicleReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    public VehicleAvailability execute(VehicleId id) {
        return reader.findById(id).orElseThrow(() -> new VehicleNotFoundException(id));
    }
}
