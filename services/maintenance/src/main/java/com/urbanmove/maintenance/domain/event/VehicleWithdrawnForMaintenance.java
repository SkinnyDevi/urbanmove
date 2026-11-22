package com.urbanmove.maintenance.domain.event;

import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.Identifiers.TechnicianId;
import com.urbanmove.maintenance.domain.Identifiers.VehicleId;
import com.urbanmove.maintenance.domain.Identifiers.ZoneId;
import com.urbanmove.maintenance.domain.MaintenanceReason;
import com.urbanmove.maintenance.domain.VehicleType;
import java.time.Instant;

/** Payload de VehicleWithdrawnForMaintenance (docs/contracts/asyncapi/schemas/payloads/). */
public record VehicleWithdrawnForMaintenance(
        VehicleId vehicleId,
        VehicleType vehicleType,
        MaintenanceOrderId maintenanceOrderId,
        MaintenanceReason reason,
        ZoneId zoneId,
        TechnicianId technicianId,
        Instant occurredAt) implements DomainEvent {
    @Override
    public String eventType() {
        return "VehicleWithdrawnForMaintenance";
    }
}
