package com.urbanmove.relocation.interfaces.http;

import com.urbanmove.relocation.domain.Identifiers.VehicleId;
import com.urbanmove.relocation.domain.RelocationOrder;
import com.urbanmove.relocation.domain.RelocationOrderStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** DTO de salida: esquema RelocationOrder de docs/contracts/openapi/relocation.yaml. */
public record OrderResponse(String relocationOrderId, String sourceZoneId, String targetZoneId, String vehicleType,
                            int quantity, String strategy, String triggeredByEventId, String status,
                            String assigneeId, List<String> vehicleIds, String createdAt, String dueAt,
                            String startedAt, String completedAt, Long delaySeconds, String cancellationReason) {

    public static OrderResponse from(RelocationOrder order) {
        RelocationOrder.Snapshot s = order.snapshot();
        return new OrderResponse(
                s.orderId().value(),
                s.route().source().value(),
                s.route().target().value(),
                s.route().vehicleType().name(),
                s.route().quantity(),
                s.strategy().name(),
                s.triggeredByEventId() == null ? null : s.triggeredByEventId().toString(),
                s.status().name(),
                s.assignee() == null ? null : s.assignee().value(),
                s.vehicleIds().stream().map(VehicleId::value).toList(),
                text(s.createdAt()),
                text(s.dueAt()),
                text(s.startedAt()),
                text(s.completedAt()),
                s.status() == RelocationOrderStatus.COMPLETED
                        ? Math.max(0, Duration.between(s.dueAt(), s.completedAt()).toSeconds()) : null,
                s.cancellationReason());
    }

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }

    public record Page(List<OrderResponse> items, String nextCursor) {
    }
}
