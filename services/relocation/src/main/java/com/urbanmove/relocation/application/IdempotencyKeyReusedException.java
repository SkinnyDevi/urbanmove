package com.urbanmove.relocation.application;

/** La Idempotency-Key ya se usó para otra operación. */
public final class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException(String key) {
        super("Idempotency-Key " + key + " was already used for a different operation");
    }
}
