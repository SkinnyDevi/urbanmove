package com.urbanmove.maintenance.interfaces.http;

import com.urbanmove.maintenance.domain.MaintenanceOrder;
import com.urbanmove.maintenance.domain.MaintenanceWork;
import java.time.Instant;
import java.util.List;

/** DTO de salida: esquema MaintenanceOrder de docs/contracts/openapi/maintenance.yaml. */
public record OrderResponse(String maintenanceOrderId, String vehicleId, String vehicleType, String reason,
                            String zoneId, String status, String technicianId, List<String> workPerformed,
                            String notes, String triggeredByEventId, String createdAt, String withdrawnAt,
                            String returnedAt, String returnZoneId, String cancellationReason) {
    public static OrderResponse from(MaintenanceOrder order) {
        MaintenanceOrder.Snapshot s = order.snapshot();
        return new OrderResponse(
                s.orderId().value(),
                s.vehicleId().value(),
                s.vehicleType().name(),
                s.reason().name(),
                s.zoneId().value(),
                s.status().name(),
                s.technicianId() == null ? null : s.technicianId().value(),
                s.workPerformed().stream().sorted().map(MaintenanceWork::name).toList(),
                s.notes(),
                s.triggeredByEventId() == null ? null : s.triggeredByEventId().toString(),
                text(s.createdAt()),
                text(s.withdrawnAt()),
                text(s.returnedAt()),
                s.returnZoneId() == null ? null : s.returnZoneId().value(),
                s.cancellationReason());
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    public record Page(List<OrderResponse> items, String nextCursor) {
    }
}
