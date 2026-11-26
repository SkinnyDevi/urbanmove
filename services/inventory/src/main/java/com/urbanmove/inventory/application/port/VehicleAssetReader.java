package com.urbanmove.inventory.application.port;

import com.urbanmove.inventory.domain.VehicleAsset;
import com.urbanmove.inventory.domain.VehicleId;
import java.util.Optional;

/** Puerto de lectura de un activo por su id. */
public interface VehicleAssetReader {
    Optional<VehicleAsset> findById(VehicleId id);
}
