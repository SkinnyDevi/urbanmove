package com.urbanmove.availability.domain;

/** Acciones que se le pueden pedir a la máquina de estados del vehículo. */
public enum VehicleAction {
    RESERVE,
    RELEASE_RESERVATION,
    START_RIDE,
    FINISH_RIDE,
    REPORT_LOW_BATTERY,
    WITHDRAW_FOR_MAINTENANCE,
    RETURN_TO_SERVICE,
    RELOCATE,
    RETIRE
}
