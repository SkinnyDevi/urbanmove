package com.urbanmove.maintenancedetection.domain;

import java.util.Optional;

/** Regla: batería por debajo del umbral, orden LOW_BATTERY para retirarlo y cargarlo. */
public final class BatteryLowRule implements DetectionRule {

    @Override
    public Optional<MaintenanceRequest> evaluate(VehicleSignal signal) {
        if (!(signal instanceof VehicleSignal.BatteryLow battery) || battery.batteryLevel() >= battery.threshold()) {
            return Optional.empty();
        }
        return Optional.of(new MaintenanceRequest(battery.vehicleId(), battery.vehicleType(),
                MaintenanceReason.LOW_BATTERY, battery.zoneId(),
                "Batería al " + battery.batteryLevel() + " % (umbral " + battery.threshold() + " %)"));
    }
}
