package com.urbanmove.maintenance.application;

/** La Idempotency-Key ya se usó para otra operación. */
public final class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String idempotencyKey) {
        super("Idempotency-Key " + idempotencyKey + " was already used for a different operation");
    }
}
