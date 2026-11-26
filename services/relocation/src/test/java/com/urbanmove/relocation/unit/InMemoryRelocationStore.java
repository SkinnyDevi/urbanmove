package com.urbanmove.relocation.unit;

import com.urbanmove.relocation.application.ActiveRouteOrderExistsException;
import com.urbanmove.relocation.application.ConcurrentUpdateException;
import com.urbanmove.relocation.application.DuplicateRequestException;
import com.urbanmove.relocation.application.port.IdempotencyRecord;
import com.urbanmove.relocation.application.port.IdempotencyStore;
import com.urbanmove.relocation.application.port.OrderPage;
import com.urbanmove.relocation.application.port.OrderSearch;
import com.urbanmove.relocation.application.port.OutgoingEvent;
import com.urbanmove.relocation.application.port.RelocationOrderReader;
import com.urbanmove.relocation.application.port.RelocationOrderSearch;
import com.urbanmove.relocation.application.port.RelocationOrderWriter;
import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.RelocationOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Almacén en memoria con las mismas condiciones que el repositorio DynamoDB. */
final class InMemoryRelocationStore
        implements RelocationOrderReader, RelocationOrderSearch, RelocationOrderWriter, IdempotencyStore {
    private final Map<RelocationOrderId, RelocationOrder.Snapshot> orders = new HashMap<>();
    private final Map<String, RelocationOrderId> activeByRoute = new HashMap<>();
    private final Map<String, IdempotencyRecord> idempotency = new HashMap<>();
    final List<OutgoingEvent> outbox = new ArrayList<>();

    @Override
    public synchronized Optional<RelocationOrder> findById(RelocationOrderId id) {
        return Optional.ofNullable(orders.get(id)).map(RelocationOrder::rehydrate);
    }

    @Override
    public synchronized OrderPage search(OrderSearch search) {
        return new OrderPage(orders.values().stream()
                .filter(o -> search.statuses().contains(o.status()))
                .filter(o -> search.targetZoneId() == null || search.targetZoneId().equals(o.route().target()))
                .sorted(Comparator.comparing(RelocationOrder.Snapshot::dueAt))
                .limit(search.limit())
                .map(RelocationOrder::rehydrate)
                .toList(), null);
    }

    @Override
    public synchronized void create(RelocationOrder order, IdempotencyRecord record, List<OutgoingEvent> events) {
        if (idempotency.containsKey(record.key())) {
            throw new DuplicateRequestException(record.key());
        }
        String route = routeKey(order);
        if (activeByRoute.containsKey(route)) {
            throw new ActiveRouteOrderExistsException(order.route(), activeByRoute.get(route));
        }
        orders.put(order.orderId(), order.snapshot());
        activeByRoute.put(route, order.orderId());
        idempotency.put(record.key(), record);
        outbox.addAll(events);
    }

    @Override
    public synchronized void update(RelocationOrder order, long expectedVersion, IdempotencyRecord record,
                                    List<OutgoingEvent> events) {
        if (idempotency.containsKey(record.key())) {
            throw new DuplicateRequestException(record.key());
        }
        RelocationOrder.Snapshot stored = orders.get(order.orderId());
        if (stored == null || stored.version() != expectedVersion) {
            throw new ConcurrentUpdateException(order.orderId());
        }
        orders.put(order.orderId(), withVersion(order.snapshot(), expectedVersion + 1));
        if (!order.isActive()) {
            activeByRoute.remove(routeKey(order));
        }
        idempotency.put(record.key(), record);
        outbox.addAll(events);
    }

    @Override
    public synchronized Optional<IdempotencyRecord> find(String key) {
        return Optional.ofNullable(idempotency.get(key));
    }

    synchronized void bumpVersion(RelocationOrderId id) {
        orders.put(id, withVersion(orders.get(id), orders.get(id).version() + 1));
    }

    private static String routeKey(RelocationOrder order) {
        return order.route().target().value() + "#" + order.route().vehicleType();
    }

    private static RelocationOrder.Snapshot withVersion(RelocationOrder.Snapshot s, long version) {
        return new RelocationOrder.Snapshot(s.orderId(), s.route(), s.strategy(), s.triggeredByEventId(), s.status(),
                s.assignee(), s.vehicleIds(), s.createdAt(), s.dueAt(), s.startedAt(), s.completedAt(),
                s.cancellationReason(), version);
    }
}
