package com.urbanmove.relocation.infrastructure.dynamo;

import static com.urbanmove.relocation.infrastructure.dynamo.RelocationTable.GSI1_PK;
import static com.urbanmove.relocation.infrastructure.dynamo.RelocationTable.GSI1_SK;
import static com.urbanmove.relocation.infrastructure.dynamo.RelocationTable.PK;

import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.Identifiers.TechnicianId;
import com.urbanmove.relocation.domain.Identifiers.VehicleId;
import com.urbanmove.relocation.domain.Identifiers.ZoneId;
import com.urbanmove.relocation.domain.RelocationOrder;
import com.urbanmove.relocation.domain.RelocationOrderStatus;
import com.urbanmove.relocation.domain.RelocationStrategy;
import com.urbanmove.relocation.domain.Route;
import com.urbanmove.relocation.domain.VehicleType;
import com.urbanmove.relocation.infrastructure.events.EventEnvelopes;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/** Conversión entre una orden de recolocación y su elemento de DynamoDB. */
final class RelocationOrderItems {

    private RelocationOrderItems() {
    }

    static String sortKey(RelocationOrder.Snapshot order) {
        return EventEnvelopes.timestamp(order.dueAt()) + "#" + order.orderId().value();
    }

    static Map<String, AttributeValue> toItem(RelocationOrder.Snapshot o, long version) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(RelocationTable.orderKey(o.orderId().value())));
        item.put(GSI1_PK, s("STATUS#" + o.status().name()));
        item.put(GSI1_SK, s(sortKey(o)));
        item.put("orderId", s(o.orderId().value()));
        item.put("sourceZoneId", s(o.route().source().value()));
        item.put("targetZoneId", s(o.route().target().value()));
        item.put("vehicleType", s(o.route().vehicleType().name()));
        item.put("quantity", n(o.route().quantity()));
        item.put("strategy", s(o.strategy().name()));
        item.put("status", s(o.status().name()));
        item.put("vehicleIds", AttributeValue.fromL(o.vehicleIds().stream().map(v -> s(v.value())).toList()));
        item.put("createdAt", s(o.createdAt().toString()));
        item.put("dueAt", s(o.dueAt().toString()));
        item.put("rowVersion", n(version));
        putIfPresent(item, "triggeredByEventId", o.triggeredByEventId(), UUID::toString);
        putIfPresent(item, "assigneeId", o.assignee(), TechnicianId::value);
        putIfPresent(item, "startedAt", o.startedAt(), Instant::toString);
        putIfPresent(item, "completedAt", o.completedAt(), Instant::toString);
        putIfPresent(item, "cancellationReason", o.cancellationReason(), Function.identity());
        return item;
    }

    static RelocationOrder toOrder(Map<String, AttributeValue> item) {
        Route route = new Route(new ZoneId(text(item, "sourceZoneId")), new ZoneId(text(item, "targetZoneId")),
                VehicleType.valueOf(text(item, "vehicleType")), Integer.parseInt(item.get("quantity").n()));
        return RelocationOrder.rehydrate(new RelocationOrder.Snapshot(
                new RelocationOrderId(text(item, "orderId")),
                route,
                RelocationStrategy.valueOf(text(item, "strategy")),
                optional(item, "triggeredByEventId", UUID::fromString),
                RelocationOrderStatus.valueOf(text(item, "status")),
                optional(item, "assigneeId", TechnicianId::new),
                item.get("vehicleIds").l().stream().map(v -> new VehicleId(v.s())).toList(),
                Instant.parse(text(item, "createdAt")),
                Instant.parse(text(item, "dueAt")),
                optional(item, "startedAt", Instant::parse),
                optional(item, "completedAt", Instant::parse),
                optional(item, "cancellationReason", Function.identity()),
                Long.parseLong(item.get("rowVersion").n())));
    }

    static AttributeValue s(String value) {
        return AttributeValue.fromS(value);
    }

    static AttributeValue n(long value) {
        return AttributeValue.fromN(Long.toString(value));
    }

    private static <T> void putIfPresent(Map<String, AttributeValue> item, String name, T value,
                                         Function<T, String> toText) {
        if (value != null) {
            item.put(name, s(toText.apply(value)));
        }
    }

    private static String text(Map<String, AttributeValue> item, String name) {
        return item.get(name).s();
    }

    private static <T> T optional(Map<String, AttributeValue> item, String name, Function<String, T> parser) {
        AttributeValue value = item.get(name);
        return value == null ? null : parser.apply(value.s());
    }
}
