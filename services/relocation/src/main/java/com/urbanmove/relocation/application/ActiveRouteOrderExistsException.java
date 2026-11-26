package com.urbanmove.relocation.application;

import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.Route;

/** Ya hay una orden activa para llevar ese tipo de vehículo a esa zona. */
public final class ActiveRouteOrderExistsException extends RuntimeException {
    private final RelocationOrderId existingOrderId;

    public ActiveRouteOrderExistsException(Route route, RelocationOrderId existingOrderId) {
        super("There is already an active relocation order to " + route.target() + " for "
                + route.vehicleType() + ": " + existingOrderId);
        this.existingOrderId = existingOrderId;
    }

    public RelocationOrderId existingOrderId() {
        return existingOrderId;
    }
}
