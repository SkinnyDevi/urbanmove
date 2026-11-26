package com.urbanmove.maintenancedetection.domain;

import java.util.Objects;

/** Señal de un vehículo que puede requerir mantenimiento. */
public sealed interface VehicleSignal {
    VehicleId vehicleId();

    record BatteryLow(VehicleId vehicleId, VehicleType vehicleType, ZoneId zoneId, int batteryLevel, int threshold)
            implements VehicleSignal {
        public BatteryLow {
            Objects.requireNonNull(vehicleId, "vehicleId");
            Objects.requireNonNull(vehicleType, "vehicleType");
            Objects.requireNonNull(zoneId, "zoneId");
            if (batteryLevel < 0 || batteryLevel > 100 || threshold < 0 || threshold > 100) {
                throw new IllegalArgumentException("batteryLevel and threshold must be between 0 and 100");
            }
        }
    }
}
