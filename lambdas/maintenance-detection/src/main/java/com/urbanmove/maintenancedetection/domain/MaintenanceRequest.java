package com.urbanmove.maintenancedetection.domain;

import java.util.Objects;

/** Orden de mantenimiento que una regla decide abrir. */
public record MaintenanceRequest(VehicleId vehicleId, VehicleType vehicleType, MaintenanceReason reason,
                                 ZoneId zoneId, String notes) {
    public MaintenanceRequest {
        Objects.requireNonNull(vehicleId, "vehicleId");
        Objects.requireNonNull(vehicleType, "vehicleType");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(zoneId, "zoneId");
    }
}
