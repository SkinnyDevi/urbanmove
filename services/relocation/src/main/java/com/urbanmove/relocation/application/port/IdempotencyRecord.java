package com.urbanmove.relocation.application.port;

import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import java.util.Objects;

/** Operación ya procesada con una clave de idempotencia y la orden a la que afectó. */
public record IdempotencyRecord(String key, String operation, RelocationOrderId orderId) {
    public IdempotencyRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(orderId, "orderId");
    }
}
