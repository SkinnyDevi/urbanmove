package com.urbanmove.maintenancedetection.domain;

import java.util.Optional;

/** Patrón Strategy: una regla mira una señal y decide si hay que abrir una orden. */
public interface DetectionRule {
    Optional<MaintenanceRequest> evaluate(VehicleSignal signal);
}
