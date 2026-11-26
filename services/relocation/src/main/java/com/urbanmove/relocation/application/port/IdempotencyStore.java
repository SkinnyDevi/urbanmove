package com.urbanmove.relocation.application.port;

import java.util.Optional;

/** Peticiones y eventos ya procesados, por clave de idempotencia. */
public interface IdempotencyStore {
    Optional<IdempotencyRecord> find(String key);
}
