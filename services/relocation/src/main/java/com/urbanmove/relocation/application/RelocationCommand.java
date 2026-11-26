package com.urbanmove.relocation.application;

import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.Identifiers.TechnicianId;
import com.urbanmove.relocation.domain.Identifiers.VehicleId;
import com.urbanmove.relocation.domain.RelocationAction;
import java.util.List;
import java.util.Objects;

/** Datos validados de una acción sobre una orden de recolocación. */
public record RelocationCommand(String idempotencyKey, String correlationId, RelocationOrderId orderId,
                                RelocationAction action, TechnicianId technician, List<VehicleId> vehicleIds,
                                String cancellationReason) {
    public RelocationCommand {
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(action, "action");
        vehicleIds = vehicleIds == null ? List.of() : List.copyOf(vehicleIds);
    }

    public static RelocationCommand byTechnician(String key, String correlationId, RelocationOrderId orderId,
                                                 RelocationAction action, TechnicianId technician) {
        return new RelocationCommand(key, correlationId, orderId, action, technician, List.of(), null);
    }
}
