package com.urbanmove.maintenance.domain;

/** Se lanza cuando se pide una acción que el estado actual de la orden no admite. */
public final class InvalidStateTransitionError extends RuntimeException {
    private final MaintenanceOrderStatus from;
    private final MaintenanceAction action;

    public InvalidStateTransitionError(MaintenanceOrderStatus from, MaintenanceAction action) {
        super("Invalid maintenance order transition: " + action + " is not allowed from " + from);
        this.from = from;
        this.action = action;
    }

    public MaintenanceOrderStatus from() {
        return from;
    }

    public MaintenanceAction action() {
        return action;
    }
}
