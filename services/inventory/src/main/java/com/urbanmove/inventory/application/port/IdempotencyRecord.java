package com.urbanmove.inventory.application.port;

import com.urbanmove.inventory.domain.VehicleId;
import java.util.Objects;

/** Operación ya procesada con una clave de idempotencia y el vehículo al que afectó. */
public record IdempotencyRecord(String key, String operation, VehicleId vehicleId) {
    public IdempotencyRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(vehicleId, "vehicleId");
    }
}
