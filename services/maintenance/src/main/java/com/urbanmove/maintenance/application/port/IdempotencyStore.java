package com.urbanmove.maintenance.application.port;

import java.util.Optional;

/** Peticiones ya procesadas, por Idempotency-Key (AGENTS.md 5.5). */
public interface IdempotencyStore {

    Optional<IdempotencyRecord> find(String idempotencyKey);
}
