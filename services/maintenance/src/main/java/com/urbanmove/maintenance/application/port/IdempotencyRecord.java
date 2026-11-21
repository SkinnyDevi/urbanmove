package com.urbanmove.maintenance.application.port;

import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import java.util.Objects;

/**
 * Una petición ya procesada: qué operación se hizo con esa Idempotency-Key y sobre qué orden.
 * {@code operation} es, por ejemplo, "OPEN" o "WITHDRAW_FOR_MAINTENANCE:mnt-...".
 */
public record IdempotencyRecord(String key, String operation, MaintenanceOrderId orderId) {

    public IdempotencyRecord {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(orderId, "orderId");
    }
}
