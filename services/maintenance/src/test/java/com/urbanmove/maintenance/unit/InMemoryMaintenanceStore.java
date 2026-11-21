package com.urbanmove.maintenance.unit;

import com.urbanmove.maintenance.application.ActiveOrderExistsException;
import com.urbanmove.maintenance.application.ConcurrentUpdateException;
import com.urbanmove.maintenance.application.DuplicateRequestException;
import com.urbanmove.maintenance.application.port.IdempotencyRecord;
import com.urbanmove.maintenance.application.port.IdempotencyStore;
import com.urbanmove.maintenance.application.port.MaintenanceOrderReader;
import com.urbanmove.maintenance.application.port.MaintenanceOrderWriter;
import com.urbanmove.maintenance.application.port.OrderPage;
import com.urbanmove.maintenance.application.port.OrderSearch;
import com.urbanmove.maintenance.application.port.OutgoingEvent;
import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.Identifiers.VehicleId;
import com.urbanmove.maintenance.domain.MaintenanceOrder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fake en memoria de los puertos (AGENTS.md 4.2, LSP): aplica las mismas condiciones que el
 * repositorio DynamoDB (una orden activa por vehículo, versión, Idempotency-Key única), así los
 * casos de uso se prueban sin red ni Docker.
 */
final class InMemoryMaintenanceStore implements MaintenanceOrderReader, MaintenanceOrderWriter, IdempotencyStore {

    private final Map<MaintenanceOrderId, MaintenanceOrder.Snapshot> orders = new HashMap<>();
    private final Map<VehicleId, MaintenanceOrderId> activeByVehicle = new HashMap<>();
    private final Map<String, IdempotencyRecord> idempotency = new HashMap<>();
    final List<OutgoingEvent> outbox = new ArrayList<>();

    @Override
    public synchronized Optional<MaintenanceOrder> findById(MaintenanceOrderId id) {
        return Optional.ofNullable(orders.get(id)).map(MaintenanceOrder::rehydrate);
    }

    @Override
    public synchronized OrderPage search(OrderSearch search) {
        List<MaintenanceOrder> found = orders.values().stream()
                .filter(s -> search.statuses().contains(s.status()))
                .filter(s -> search.zoneId() == null || search.zoneId().equals(s.zoneId()))
                .filter(s -> search.vehicleId() == null || search.vehicleId().equals(s.vehicleId()))
                .filter(s -> search.technicianId() == null || search.technicianId().equals(s.technicianId()))
                .sorted(Comparator.comparing(MaintenanceOrder.Snapshot::createdAt))
                .map(MaintenanceOrder::rehydrate)
                .limit(search.limit())
                .toList();
        return new OrderPage(found, null);
    }

    @Override
    public synchronized void create(MaintenanceOrder order, IdempotencyRecord record, List<OutgoingEvent> events) {
        if (idempotency.containsKey(record.key())) {
            throw new DuplicateRequestException(record.key());
        }
        MaintenanceOrderId active = activeByVehicle.get(order.vehicleId());
        if (active != null) {
            throw new ActiveOrderExistsException(order.vehicleId(), active);
        }
        orders.put(order.orderId(), order.snapshot());
        activeByVehicle.put(order.vehicleId(), order.orderId());
        idempotency.put(record.key(), record);
        outbox.addAll(events);
    }

    @Override
    public synchronized void update(MaintenanceOrder order, long expectedVersion, IdempotencyRecord record,
                                    List<OutgoingEvent> events) {
        if (idempotency.containsKey(record.key())) {
            throw new DuplicateRequestException(record.key());
        }
        MaintenanceOrder.Snapshot stored = orders.get(order.orderId());
        if (stored == null || stored.version() != expectedVersion) {
            throw new ConcurrentUpdateException(order.orderId());
        }
        MaintenanceOrder.Snapshot s = order.snapshot();
        orders.put(order.orderId(), new MaintenanceOrder.Snapshot(s.orderId(), s.vehicleId(), s.vehicleType(),
                s.reason(), s.zoneId(), s.status(), s.technicianId(), s.workPerformed(), s.notes(),
                s.triggeredByEventId(), s.createdAt(), s.withdrawnAt(), s.returnedAt(), s.returnZoneId(),
                s.cancellationReason(), expectedVersion + 1));
        if (!order.isActive()) {
            activeByVehicle.remove(order.vehicleId());
        }
        idempotency.put(record.key(), record);
        outbox.addAll(events);
    }

    @Override
    public synchronized Optional<IdempotencyRecord> find(String key) {
        return Optional.ofNullable(idempotency.get(key));
    }

    /** Simula que otra petición cambió la orden entre la lectura y la escritura. */
    synchronized void bumpVersion(MaintenanceOrderId id) {
        MaintenanceOrder.Snapshot s = orders.get(id);
        orders.put(id, new MaintenanceOrder.Snapshot(s.orderId(), s.vehicleId(), s.vehicleType(), s.reason(),
                s.zoneId(), s.status(), s.technicianId(), s.workPerformed(), s.notes(), s.triggeredByEventId(),
                s.createdAt(), s.withdrawnAt(), s.returnedAt(), s.returnZoneId(), s.cancellationReason(),
                s.version() + 1));
    }
}
