package com.urbanmove.zones.infrastructure.persistence;

/** Fallo técnico de la base de datos. */
public final class PersistenceException extends RuntimeException {
    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
