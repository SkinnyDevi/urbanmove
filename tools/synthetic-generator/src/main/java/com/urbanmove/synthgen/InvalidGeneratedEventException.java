package com.urbanmove.synthgen;

/** El generador ha producido un evento que no cumple los contratos: es un error del generador. */
public class InvalidGeneratedEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidGeneratedEventException(String message) {
        super(message);
    }
}
