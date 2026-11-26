package com.urbanmove.inventory.application.port;

import java.util.Optional;

/** Peticiones ya procesadas, por clave de idempotencia. */
public interface IdempotencyStore {
    Optional<IdempotencyRecord> find(String key);
}
