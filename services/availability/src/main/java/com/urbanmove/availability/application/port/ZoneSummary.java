package com.urbanmove.availability.application.port;

import com.urbanmove.availability.domain.VehicleStatus;
import com.urbanmove.availability.domain.VehicleType;
import com.urbanmove.availability.domain.ZoneId;
import java.util.EnumMap;
import java.util.Map;

/** Recuento de vehículos por estado en una zona y tipo. */
public record ZoneSummary(ZoneId zoneId, VehicleType vehicleType, Map<VehicleStatus, Integer> counts) {
    public ZoneSummary {
        EnumMap<VehicleStatus, Integer> all = new EnumMap<>(VehicleStatus.class);
        for (VehicleStatus status : VehicleStatus.values()) {
            all.put(status, counts.getOrDefault(status, 0));
        }
        counts = java.util.Collections.unmodifiableMap(all);
    }
}
