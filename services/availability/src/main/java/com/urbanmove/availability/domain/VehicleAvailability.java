package com.urbanmove.availability.domain;

import java.time.Instant;
import java.util.Objects;

/** Agregado de availability: dónde está un vehículo y si se puede reservar. */
public final class VehicleAvailability {
    public record Snapshot(VehicleId vehicleId, VehicleType vehicleType, ZoneId zoneId, VehicleStatus status,
                           boolean batteryLow, Instant updatedAt, long version) {
    }

    private final VehicleId vehicleId;
    private final VehicleType vehicleType;
    private final Instant updatedAt;
    private final long version;
    private ZoneId zoneId;
    private VehicleState state;
    private boolean batteryLow;

    private VehicleAvailability(Snapshot s) {
        this.vehicleId = Objects.requireNonNull(s.vehicleId(), "vehicleId");
        this.vehicleType = Objects.requireNonNull(s.vehicleType(), "vehicleType");
        this.zoneId = Objects.requireNonNull(s.zoneId(), "zoneId");
        this.state = VehicleState.of(Objects.requireNonNull(s.status(), "status"));
        this.updatedAt = Objects.requireNonNull(s.updatedAt(), "updatedAt");
        this.batteryLow = s.batteryLow();
        this.version = s.version();
    }

    public static VehicleAvailability registerPurchased(VehicleId vehicleId, VehicleType vehicleType,
                                                        ZoneId initialZone, Instant at) {
        return new VehicleAvailability(new Snapshot(vehicleId, vehicleType, initialZone, VehicleStatus.AVAILABLE,
                false, at, 0));
    }

    public static VehicleAvailability rehydrate(Snapshot snapshot) {
        return new VehicleAvailability(snapshot);
    }

    public Snapshot snapshot() {
        return new Snapshot(vehicleId, vehicleType, zoneId, state.status(), batteryLow, updatedAt, version);
    }

    public void reserve() {
        state = state.reserve();
    }

    public void releaseReservation() {
        state = state.releaseReservation(batteryLow);
    }

    public void startRide() {
        state = state.startRide();
    }

    public void finishRide(ZoneId destination) {
        Objects.requireNonNull(destination, "destination");
        state = state.finishRide(batteryLow);
        zoneId = destination;
    }

    public void reportLowBattery() {
        state = state.reportLowBattery();
        batteryLow = true;
    }

    public void withdrawForMaintenance() {
        state = state.withdrawForMaintenance();
    }

    public void returnToService(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        state = state.returnToService();
        zoneId = zone;
        batteryLow = false;
    }

    public void relocateTo(ZoneId destination) {
        Objects.requireNonNull(destination, "destination");
        state = state.relocate();
        zoneId = destination;
    }

    public void retire() {
        state = state.retire();
    }

    public boolean isReservable() {
        return state.status() == VehicleStatus.AVAILABLE;
    }

    public VehicleId vehicleId() { return vehicleId; }

    public VehicleType vehicleType() { return vehicleType; }

    public ZoneId zoneId() { return zoneId; }

    public VehicleStatus status() { return state.status(); }

    public boolean batteryLow() { return batteryLow; }

    public long version() { return version; }
}
