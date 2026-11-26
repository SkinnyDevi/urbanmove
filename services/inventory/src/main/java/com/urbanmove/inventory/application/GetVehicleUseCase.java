package com.urbanmove.inventory.application;

import com.urbanmove.inventory.application.port.VehicleAssetReader;
import com.urbanmove.inventory.domain.VehicleAsset;
import com.urbanmove.inventory.domain.VehicleId;
import java.util.Objects;

/** Caso de uso: obtener un activo por su id. */
public final class GetVehicleUseCase {
    private final VehicleAssetReader reader;

    public GetVehicleUseCase(VehicleAssetReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    public VehicleAsset execute(VehicleId id) {
        return reader.findById(id).orElseThrow(() -> new VehicleNotFoundException(id));
    }
}
