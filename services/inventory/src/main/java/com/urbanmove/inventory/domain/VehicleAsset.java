package com.urbanmove.inventory.domain;

import com.urbanmove.inventory.domain.event.DomainEvent;
import com.urbanmove.inventory.domain.event.VehicleRetired;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Agregado de inventory: un vehículo como activo de la flota, desde la compra hasta la baja. */
public final class VehicleAsset {
    public static final int MAX_REASON = 500;

    public record Snapshot(VehicleId vehicleId, VehicleType vehicleType, String model, LocalDate purchaseDate,
                           Money purchasePrice, ServiceStatus status, Instant registeredAt, Instant retiredAt,
                           String retirementReason, long version) {
    }

    private final Snapshot purchase;
    private final List<DomainEvent> events = new ArrayList<>();
    private AssetState state;
    private Instant retiredAt;
    private String retirementReason;

    private VehicleAsset(Snapshot s) {
        Objects.requireNonNull(s.vehicleId(), "vehicleId");
        Objects.requireNonNull(s.vehicleType(), "vehicleType");
        Objects.requireNonNull(s.purchaseDate(), "purchaseDate");
        Objects.requireNonNull(s.registeredAt(), "registeredAt");
        if (s.model() == null || s.model().isBlank()) {
            throw new IllegalArgumentException("model is required");
        }
        this.purchase = s;
        this.state = AssetState.of(Objects.requireNonNull(s.status(), "status"));
        this.retiredAt = s.retiredAt();
        this.retirementReason = s.retirementReason();
    }

    public static VehicleAsset registerPurchased(VehicleId id, VehicleType type, String model, LocalDate purchaseDate,
                                                 Money price, Instant registeredAt) {
        return new VehicleAsset(new Snapshot(id, type, model, purchaseDate, price, ServiceStatus.IN_SERVICE,
                registeredAt, null, null, 0));
    }

    public static VehicleAsset rehydrate(Snapshot snapshot) {
        return new VehicleAsset(snapshot);
    }

    public void markWithdrawn() {
        state = state.withdraw();
    }

    public void markReturnedToService() {
        state = state.returnToService();
    }

    public void retire(String reason, Instant at) {
        if (reason == null || reason.isBlank() || reason.length() > MAX_REASON) {
            throw new IllegalArgumentException("reason must have between 1 and " + MAX_REASON + " characters");
        }
        state = state.retire();
        retiredAt = Objects.requireNonNull(at, "at");
        retirementReason = reason;
        events.add(new VehicleRetired(purchase.vehicleId(), purchase.vehicleType(), reason, at));
    }

    public List<DomainEvent> pullDomainEvents() {
        List<DomainEvent> pulled = List.copyOf(events);
        events.clear();
        return pulled;
    }

    public Snapshot snapshot() {
        return new Snapshot(purchase.vehicleId(), purchase.vehicleType(), purchase.model(), purchase.purchaseDate(),
                purchase.purchasePrice(), state.status(), purchase.registeredAt(), retiredAt, retirementReason,
                purchase.version());
    }

    public VehicleId vehicleId() {
        return purchase.vehicleId();
    }

    public ServiceStatus status() {
        return state.status();
    }

    public AdminStatus adminStatus() {
        return AdminStatus.of(state.status());
    }

    public long version() {
        return purchase.version();
    }
}
