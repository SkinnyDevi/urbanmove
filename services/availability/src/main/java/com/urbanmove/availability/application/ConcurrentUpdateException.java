package com.urbanmove.availability.application;

/** Otro evento modificó el mismo vehículo entre la lectura y la escritura (bloqueo optimista). */
public final class ConcurrentUpdateException extends RuntimeException {
    public ConcurrentUpdateException(String detail) {
        super(detail);
    }
}
