package com.urbanmove.availability.domain;

/** Se lanza cuando se pide una acción que el estado actual del vehículo no admite. */
public final class InvalidStateTransitionError extends RuntimeException {
    private final VehicleStatus from;
    private final VehicleAction action;

    public InvalidStateTransitionError(VehicleStatus from, VehicleAction action) {
        super("Invalid vehicle transition: " + action + " is not allowed from " + from);
        this.from = from;
        this.action = action;
    }

    public VehicleStatus from() {
        return from;
    }

    public VehicleAction action() {
        return action;
    }
}
