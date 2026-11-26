package com.urbanmove.relocation.domain.event;

import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.Identifiers.ZoneId;
import com.urbanmove.relocation.domain.RelocationStrategy;
import com.urbanmove.relocation.domain.VehicleType;
import java.time.Instant;
import java.util.UUID;

/** Payload de RelocationOrderCreated (docs/contracts/asyncapi). */
public record RelocationOrderCreated(RelocationOrderId relocationOrderId, ZoneId sourceZoneId, ZoneId targetZoneId,
                                     VehicleType vehicleType, int quantity, RelocationStrategy strategy,
                                     UUID triggeredByEventId, Instant occurredAt) implements DomainEvent {
    @Override
    public String eventType() {
        return "RelocationOrderCreated";
    }
}
