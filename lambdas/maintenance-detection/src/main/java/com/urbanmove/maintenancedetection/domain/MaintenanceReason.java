package com.urbanmove.maintenancedetection.domain;

/** Motivo de una orden de mantenimiento, como en el contrato maintenance.yaml. */
public enum MaintenanceReason {
    LOW_BATTERY,
    BREAKDOWN,
    EXCESS_USAGE,
    INSPECTION,
    VANDALISM
}
