package com.urbanmove.zones.infrastructure.persistence;

/** Fallo técnico de la base de datos. La capa HTTP lo convierte en un 500 (con el detalle solo en el log). */
public final class PersistenceException extends RuntimeException {

    public PersistenceException(String message, Throwable cause) {
        super(message, cause);
    }
}
