package com.urbanmove.maintenance.domain.event;

import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.Identifiers.VehicleId;
import com.urbanmove.maintenance.domain.Identifiers.ZoneId;
import com.urbanmove.maintenance.domain.MaintenanceWork;
import com.urbanmove.maintenance.domain.VehicleType;
import java.time.Instant;
import java.util.List;

/** Payload de VehicleReturnedToService (docs/contracts/asyncapi/schemas/payloads/). */
public record VehicleReturnedToService(
        VehicleId vehicleId,
        VehicleType vehicleType,
        MaintenanceOrderId maintenanceOrderId,
        List<MaintenanceWork> workPerformed,
        ZoneId zoneId,
        long outOfServiceSeconds,
        Instant occurredAt) implements DomainEvent {

    public VehicleReturnedToService {
        workPerformed = List.copyOf(workPerformed);
    }

    @Override
    public String eventType() {
        return "VehicleReturnedToService";
    }
}
