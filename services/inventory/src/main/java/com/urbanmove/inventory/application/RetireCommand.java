package com.urbanmove.inventory.application;

import com.urbanmove.inventory.domain.VehicleId;
import java.util.Objects;

/** Datos validados de una baja definitiva. */
public record RetireCommand(String idempotencyKey, String correlationId, VehicleId vehicleId, String reason) {
    public RetireCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(vehicleId, "vehicleId");
        Objects.requireNonNull(reason, "reason");
    }
}
