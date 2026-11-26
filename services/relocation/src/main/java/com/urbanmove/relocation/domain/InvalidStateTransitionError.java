package com.urbanmove.relocation.domain;

/** La acción pedida no está permitida en el estado actual de la orden. */
public final class InvalidStateTransitionError extends RuntimeException {
    private final RelocationOrderStatus from;
    private final RelocationAction action;

    public InvalidStateTransitionError(RelocationOrderStatus from, RelocationAction action) {
        super("Invalid relocation order transition: " + action + " is not allowed from " + from);
        this.from = from;
        this.action = action;
    }

    public RelocationOrderStatus from() {
        return from;
    }

    public RelocationAction action() {
        return action;
    }
}
