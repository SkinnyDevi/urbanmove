package com.urbanmove.maintenance.application;

/** Otra petición con la misma Idempotency-Key se guardó justo antes (dos envíos simultáneos). */
public final class DuplicateRequestException extends RuntimeException {
    public DuplicateRequestException(String idempotencyKey) {
        super("Idempotency-Key already used: " + idempotencyKey);
    }
}
