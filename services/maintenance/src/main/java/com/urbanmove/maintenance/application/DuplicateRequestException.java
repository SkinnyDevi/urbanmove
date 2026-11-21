package com.urbanmove.maintenance.application;

/**
 * Otra petición con la misma Idempotency-Key se guardó justo antes (dos envíos simultáneos).
 * No llega al cliente: el caso de uso la captura y devuelve el resultado de la primera.
 */
public final class DuplicateRequestException extends RuntimeException {

    public DuplicateRequestException(String idempotencyKey) {
        super("Idempotency-Key already used: " + idempotencyKey);
    }
}
