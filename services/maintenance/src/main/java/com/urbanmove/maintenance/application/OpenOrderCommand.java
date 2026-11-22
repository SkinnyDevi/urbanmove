package com.urbanmove.maintenance.application;

import com.urbanmove.maintenance.domain.Identifiers.VehicleId;
import com.urbanmove.maintenance.domain.Identifiers.ZoneId;
import com.urbanmove.maintenance.domain.MaintenanceReason;
import com.urbanmove.maintenance.domain.VehicleType;
import java.util.Objects;
import java.util.UUID;

/** Datos validados para abrir una orden de mantenimiento. */
public record OpenOrderCommand(String idempotencyKey, String correlationId, VehicleId vehicleId,
                               VehicleType vehicleType, MaintenanceReason reason, ZoneId zoneId,
                               String notes, UUID triggeredByEventId) {
    public OpenOrderCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(vehicleId, "vehicleId");
        Objects.requireNonNull(vehicleType, "vehicleType");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(zoneId, "zoneId");
    }
}
