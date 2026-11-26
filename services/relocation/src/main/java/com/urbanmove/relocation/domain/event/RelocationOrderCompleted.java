package com.urbanmove.relocation.domain.event;

import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.Identifiers.VehicleId;
import com.urbanmove.relocation.domain.Identifiers.ZoneId;
import com.urbanmove.relocation.domain.VehicleType;
import java.time.Instant;
import java.util.List;

/** Payload de RelocationOrderCompleted (docs/contracts/asyncapi). */
public record RelocationOrderCompleted(RelocationOrderId relocationOrderId, ZoneId sourceZoneId, ZoneId targetZoneId,
                                       VehicleType vehicleType, List<VehicleId> vehicleIds, Instant createdAt,
                                       Instant completedAt, long delaySeconds) implements DomainEvent {
    public RelocationOrderCompleted {
        vehicleIds = List.copyOf(vehicleIds);
    }

    @Override
    public String eventType() {
        return "RelocationOrderCompleted";
    }

    @Override
    public Instant occurredAt() {
        return completedAt;
    }
}
