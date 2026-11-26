package com.urbanmove.inventory.domain.event;

import com.urbanmove.inventory.domain.VehicleId;
import com.urbanmove.inventory.domain.VehicleType;
import java.time.Instant;
import java.util.Objects;

/** El activo se ha dado de baja definitiva. */
public record VehicleRetired(VehicleId vehicleId, VehicleType vehicleType, String reason, Instant occurredAt)
        implements DomainEvent {
    public VehicleRetired {
        Objects.requireNonNull(vehicleId);
        Objects.requireNonNull(vehicleType);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(occurredAt);
    }

    @Override
    public String eventType() {
        return "VehicleRetired";
    }
}
