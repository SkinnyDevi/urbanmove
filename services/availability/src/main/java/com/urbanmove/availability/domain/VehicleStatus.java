package com.urbanmove.availability.domain;

/** Estados operativos del vehículo de los que availability es dueño. */
public enum VehicleStatus {
    AVAILABLE,
    RESERVED,
    IN_USE,
    LOW_BATTERY,
    WITHDRAWN,
    RETIRED
}
