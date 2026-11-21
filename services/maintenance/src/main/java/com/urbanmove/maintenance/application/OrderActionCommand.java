package com.urbanmove.maintenance.application;

import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.Identifiers.TechnicianId;
import com.urbanmove.maintenance.domain.Identifiers.ZoneId;
import com.urbanmove.maintenance.domain.MaintenanceAction;
import java.util.Objects;

/**
 * POST /maintenance-orders/{id}/{acción}. Solo algunas acciones usan algunos campos:
 * {@code technician} para retirar, {@code returnZone} para devolver, {@code cancellationReason} para cancelar.
 */
public record OrderActionCommand(String idempotencyKey, String correlationId, MaintenanceOrderId orderId,
                                 MaintenanceAction action, TechnicianId technician, ZoneId returnZone,
                                 String cancellationReason) {

    public OrderActionCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(action, "action");
        switch (action) {
            case WITHDRAW_FOR_MAINTENANCE -> Objects.requireNonNull(technician, "technician");
            case RETURN_TO_SERVICE -> Objects.requireNonNull(returnZone, "returnZone");
            case CANCEL -> Objects.requireNonNull(cancellationReason, "cancellationReason");
            default -> { }
        }
    }

    public static OrderActionCommand simple(String idempotencyKey, String correlationId,
                                            MaintenanceOrderId orderId, MaintenanceAction action) {
        return new OrderActionCommand(idempotencyKey, correlationId, orderId, action, null, null, null);
    }
}
