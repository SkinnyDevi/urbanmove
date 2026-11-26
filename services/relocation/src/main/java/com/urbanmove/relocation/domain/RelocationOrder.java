package com.urbanmove.relocation.domain;

import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.Identifiers.TechnicianId;
import com.urbanmove.relocation.domain.Identifiers.VehicleId;
import com.urbanmove.relocation.domain.event.DomainEvent;
import com.urbanmove.relocation.domain.event.RelocationOrderCompleted;
import com.urbanmove.relocation.domain.event.RelocationOrderCreated;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Agregado orden de recolocación: creada → asignada → en curso → completada (o cancelada). */
public final class RelocationOrder {

    public static final int MAX_REASON = 500;

    public record Snapshot(RelocationOrderId orderId, Route route, RelocationStrategy strategy, UUID triggeredByEventId,
                           RelocationOrderStatus status, TechnicianId assignee, List<VehicleId> vehicleIds,
                           Instant createdAt, Instant dueAt, Instant startedAt, Instant completedAt,
                           String cancellationReason, long version) {
        public Snapshot {
            vehicleIds = vehicleIds == null ? List.of() : List.copyOf(vehicleIds);
        }
    }

    private final RelocationOrderId orderId;
    private final Route route;
    private final RelocationStrategy strategy;
    private final UUID triggeredByEventId;
    private final Instant createdAt;
    private final Instant dueAt;
    private final long version;
    private RelocationOrderState state;
    private TechnicianId assignee;
    private List<VehicleId> vehicleIds;
    private Instant startedAt;
    private Instant completedAt;
    private String cancellationReason;
    private final List<DomainEvent> pendingEvents = new ArrayList<>();

    private RelocationOrder(Snapshot s) {
        this.orderId = Objects.requireNonNull(s.orderId(), "orderId");
        this.route = Objects.requireNonNull(s.route(), "route");
        this.strategy = Objects.requireNonNull(s.strategy(), "strategy");
        this.createdAt = Objects.requireNonNull(s.createdAt(), "createdAt");
        this.dueAt = Objects.requireNonNull(s.dueAt(), "dueAt");
        this.state = RelocationOrderState.of(Objects.requireNonNull(s.status(), "status"));
        this.triggeredByEventId = s.triggeredByEventId();
        this.assignee = s.assignee();
        this.vehicleIds = s.vehicleIds();
        this.startedAt = s.startedAt();
        this.completedAt = s.completedAt();
        this.cancellationReason = s.cancellationReason();
        this.version = s.version();
    }

    public static RelocationOrder create(RelocationOrderId orderId, Route route, RelocationStrategy strategy,
                                         UUID triggeredByEventId, Instant createdAt, Instant dueAt) {
        RelocationOrder order = new RelocationOrder(new Snapshot(orderId, route, strategy, triggeredByEventId,
                RelocationOrderStatus.CREATED, null, List.of(), createdAt, dueAt, null, null, null, 0));
        order.pendingEvents.add(new RelocationOrderCreated(orderId, route.source(), route.target(),
                route.vehicleType(), route.quantity(), strategy, triggeredByEventId, createdAt));
        return order;
    }

    public static RelocationOrder rehydrate(Snapshot snapshot) {
        return new RelocationOrder(snapshot);
    }

    public Snapshot snapshot() {
        return new Snapshot(orderId, route, strategy, triggeredByEventId, state.status(), assignee, vehicleIds,
                createdAt, dueAt, startedAt, completedAt, cancellationReason, version);
    }

    public void assign(TechnicianId technician) {
        Objects.requireNonNull(technician, "technician");
        state = state.assign();
        assignee = technician;
    }

    public void start(TechnicianId technician, Instant at) {
        requireAssignee(technician);
        state = state.start();
        startedAt = Objects.requireNonNull(at, "at");
    }

    public void complete(TechnicianId technician, List<VehicleId> moved, Instant at) {
        requireAssignee(technician);
        List<VehicleId> vehicles = validMovedVehicles(moved);
        state = state.complete();
        vehicleIds = vehicles;
        completedAt = Objects.requireNonNull(at, "at");
        long delaySeconds = Math.max(0, Duration.between(dueAt, at).toSeconds());
        pendingEvents.add(new RelocationOrderCompleted(orderId, route.source(), route.target(), route.vehicleType(),
                vehicles, createdAt, at, delaySeconds));
    }

    public void cancel(String reason) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON) {
            throw new InvalidRelocationException("A cancellation reason of 1 to " + MAX_REASON + " characters is required");
        }
        state = state.cancel();
        cancellationReason = reason;
    }

    public boolean isActive() {
        return state.isActive();
    }

    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> events = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return events;
    }

    public RelocationOrderId orderId() { return orderId; }

    public Route route() { return route; }

    public RelocationOrderStatus status() { return state.status(); }

    public long version() { return version; }

    private void requireAssignee(TechnicianId technician) {
        if (assignee != null && !assignee.equals(technician)) {
            throw new NotAssigneeException(orderId, technician);
        }
    }

    private List<VehicleId> validMovedVehicles(List<VehicleId> moved) {
        if (moved == null || moved.isEmpty()) {
            throw new InvalidRelocationException("At least one moved vehicle is required");
        }
        List<VehicleId> unique = List.copyOf(new LinkedHashSet<>(moved));
        if (unique.size() != moved.size()) {
            throw new InvalidRelocationException("Moved vehicles must not be repeated");
        }
        if (unique.size() > route.quantity()) {
            throw new InvalidRelocationException("Cannot move more than " + route.quantity() + " vehicles");
        }
        return unique;
    }
}
