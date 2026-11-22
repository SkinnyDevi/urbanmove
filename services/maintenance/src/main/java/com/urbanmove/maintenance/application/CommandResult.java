package com.urbanmove.maintenance.application;

import com.urbanmove.maintenance.domain.MaintenanceOrder;

/** Resultado de una orden de escritura. */
public record CommandResult(MaintenanceOrder order, boolean replayed) {
}
