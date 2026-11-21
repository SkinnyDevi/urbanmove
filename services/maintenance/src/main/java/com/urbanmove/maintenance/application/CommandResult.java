package com.urbanmove.maintenance.application;

import com.urbanmove.maintenance.domain.MaintenanceOrder;

/**
 * Resultado de una orden de escritura. {@code replayed} es true si la Idempotency-Key ya se
 * había usado y se devuelve el resultado anterior sin repetir el efecto.
 */
public record CommandResult(MaintenanceOrder order, boolean replayed) {
}
