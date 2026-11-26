package com.urbanmove.relocation.application;

/** Otra petición con la misma clave de idempotencia se guardó justo antes. */
public final class DuplicateRequestException extends RuntimeException {
    public DuplicateRequestException(String key) {
        super("Idempotency key already used: " + key);
    }
}
