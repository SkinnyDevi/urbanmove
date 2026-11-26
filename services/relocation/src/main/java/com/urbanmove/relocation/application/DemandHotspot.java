package com.urbanmove.relocation.application;

import com.urbanmove.relocation.domain.RelocationStrategy;
import com.urbanmove.relocation.domain.VehicleType;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Déficit detectado por la Lambda relocation-engine, tal como llega en DemandHotspotDetected. */
public record DemandHotspot(UUID eventId, String correlationId, Instant detectedAt, String deficitZoneId,
                            String suggestedSourceZoneId, VehicleType vehicleType, int suggestedQuantity,
                            RelocationStrategy strategy) {
    public DemandHotspot {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(detectedAt, "detectedAt");
        Objects.requireNonNull(vehicleType, "vehicleType");
        Objects.requireNonNull(strategy, "strategy");
    }
}
