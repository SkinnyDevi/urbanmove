package com.urbanmove.inventory.domain;

/** La acción pedida no está permitida en el estado actual del activo. */
public final class InvalidStateTransitionError extends RuntimeException {
    private final ServiceStatus from;
    private final AssetAction action;

    public InvalidStateTransitionError(ServiceStatus from, AssetAction action) {
        super("Invalid vehicle transition: " + action + " is not allowed from " + from);
        this.from = from;
        this.action = action;
    }

    public ServiceStatus from() {
        return from;
    }

    public AssetAction action() {
        return action;
    }
}
