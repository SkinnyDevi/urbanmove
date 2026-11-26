package com.urbanmove.relocation.domain;

/** Los datos de la orden o de su cierre no cumplen las reglas de la recolocación. */
public final class InvalidRelocationException extends RuntimeException {
    public InvalidRelocationException(String message) {
        super(message);
    }
}
