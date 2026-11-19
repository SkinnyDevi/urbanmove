package com.urbanmove.maintenance.domain;

/** Motivo de la orden (common.schema.json: maintenanceReason). */
public enum MaintenanceReason {
    LOW_BATTERY,
    BREAKDOWN,
    EXCESS_USAGE,
    INSPECTION,
    VANDALISM
}
