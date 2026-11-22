package com.urbanmove.availability.interfaces.http;

import com.urbanmove.availability.application.port.ZoneSummary;
import com.urbanmove.availability.domain.VehicleAvailability;
import com.urbanmove.availability.domain.VehicleStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** DTOs de salida: esquemas de docs/contracts/openapi/availability.yaml. */
public final class VehicleResponses {
    private VehicleResponses() {
    }

    public record Vehicle(String vehicleId, String vehicleType, String zoneId, String status, boolean reservable,
                          boolean batteryLow, String updatedAt) {
        public static Vehicle from(VehicleAvailability vehicle) {
            VehicleAvailability.Snapshot s = vehicle.snapshot();
            return new Vehicle(s.vehicleId().value(), s.vehicleType().name(), s.zoneId().value(), s.status().name(),
                    vehicle.isReservable(), s.batteryLow(), s.updatedAt().toString());
        }
    }

    public record Page(List<Vehicle> items, String nextCursor) {
    }

    public record Zone(String zoneId, String vehicleType, Map<String, Integer> counts) {
        public static Zone from(ZoneSummary summary) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (VehicleStatus status : VehicleStatus.values()) {
                counts.put(status.name(), summary.counts().get(status));
            }
            return new Zone(summary.zoneId().value(), summary.vehicleType().name(), counts);
        }
    }

    public record Summary(String generatedAt, List<Zone> items) {
    }
}
