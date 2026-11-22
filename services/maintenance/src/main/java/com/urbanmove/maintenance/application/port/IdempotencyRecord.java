package com.urbanmove.maintenance.application.port;

import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import java.util.Objects;

/** Petición ya procesada: operación y orden asociadas a una Idempotency-Key. */
public record IdempotencyRecord(String key, String operation, MaintenanceOrderId orderId) {
    public IdempotencyRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(orderId, "orderId");
    }
}
