package com.urbanmove.inventory.application;

import com.urbanmove.inventory.domain.VehicleId;

/** Otra petición o evento modificó el activo entre la lectura y la escritura. */
public final class ConcurrentUpdateException extends RuntimeException {
    public ConcurrentUpdateException(VehicleId id) {
        super("Vehicle " + id + " was modified at the same time; read it again and retry");
    }
}
