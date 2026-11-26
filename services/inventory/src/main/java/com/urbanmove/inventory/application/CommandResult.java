package com.urbanmove.inventory.application;

import com.urbanmove.inventory.domain.VehicleAsset;

/** Resultado de una escritura; replayed indica que la clave ya se había usado. */
public record CommandResult(VehicleAsset vehicle, boolean replayed) {
}
