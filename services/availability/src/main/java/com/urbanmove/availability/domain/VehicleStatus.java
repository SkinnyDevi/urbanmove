package com.urbanmove.availability.domain;

/**
 * Estados operativos del vehículo de los que availability es dueño.
 *
 * <p>Son 6 de los 9 estados oficiales (AGENTS.md 3.4). Los otros 3 (CHARGING, REPAIRING,
 * READY_FOR_SERVICE) ocurren dentro del taller y son estados de la orden de mantenimiento
 * (servicio maintenance): para el resto del sistema el vehículo está WITHDRAWN hasta que
 * llega VehicleReturnedToService. Ver docs/architecture/vehicle-state-machine.md.
 */
public enum VehicleStatus {
    AVAILABLE,
    RESERVED,
    IN_USE,
    LOW_BATTERY,
    WITHDRAWN,
    RETIRED
}
