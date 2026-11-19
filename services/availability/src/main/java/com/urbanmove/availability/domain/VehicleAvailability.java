package com.urbanmove.availability.domain;

import java.util.Objects;

/**
 * Agregado de availability: dónde está un vehículo y si se puede reservar.
 *
 * <p>Tell, don't ask (AGENTS.md 4.3): quien lo usa le dice lo que ha pasado
 * ({@code reserve()}, {@code finishRide(zona)}...) y el agregado decide el nuevo estado a
 * través de {@link VehicleState}. Nadie fija el estado desde fuera.
 */
public final class VehicleAvailability {

    private final VehicleId vehicleId;
    private final VehicleType vehicleType;
    private ZoneId zoneId;
    private VehicleState state;
    private boolean batteryLow;

    private VehicleAvailability(VehicleId vehicleId, VehicleType vehicleType, ZoneId zoneId,
                                VehicleState state, boolean batteryLow) {
        this.vehicleId = Objects.requireNonNull(vehicleId, "vehicleId");
        this.vehicleType = Objects.requireNonNull(vehicleType, "vehicleType");
        this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
        this.state = Objects.requireNonNull(state, "state");
        this.batteryLow = batteryLow;
    }

    /** Vehículo nuevo (VehiclePurchased): entra disponible en su zona inicial. */
    public static VehicleAvailability registerPurchased(VehicleId vehicleId, VehicleType vehicleType, ZoneId initialZone) {
        return new VehicleAvailability(vehicleId, vehicleType, initialZone, VehicleState.Available.INSTANCE, false);
    }

    /** Reconstrucción desde la base de datos (solo la usa el repositorio). */
    public static VehicleAvailability rehydrate(VehicleId vehicleId, VehicleType vehicleType, ZoneId zoneId,
                                                VehicleStatus status, boolean batteryLow) {
        Objects.requireNonNull(status, "status");
        return new VehicleAvailability(vehicleId, vehicleType, zoneId, VehicleState.of(status), batteryLow);
    }

    /** VehicleReserved. */
    public void reserve() {
        state = state.reserve();
    }

    /** ReservationCancelled o ReservationExpired. */
    public void releaseReservation() {
        state = state.releaseReservation(batteryLow);
    }

    /** RideStarted. */
    public void startRide() {
        state = state.startRide();
    }

    /** RideFinished: el vehículo queda en la zona de destino. */
    public void finishRide(ZoneId destination) {
        Objects.requireNonNull(destination, "destination");
        state = state.finishRide(batteryLow);
        zoneId = destination;
    }

    /** VehicleBatteryLow. Si está reservado o en uso, se anota y se aplica al liberarse. */
    public void reportLowBattery() {
        state = state.reportLowBattery();
        batteryLow = true;
    }

    /** VehicleWithdrawnForMaintenance. */
    public void withdrawForMaintenance() {
        state = state.withdrawForMaintenance();
    }

    /** VehicleReturnedToService: vuelve cargado y revisado, quizá en otra zona. */
    public void returnToService(ZoneId zone) {
        Objects.requireNonNull(zone, "zone");
        state = state.returnToService();
        zoneId = zone;
        batteryLow = false;
    }

    /** RelocationOrderCompleted: el técnico lo ha movido a otra zona. */
    public void relocateTo(ZoneId destination) {
        Objects.requireNonNull(destination, "destination");
        state = state.relocate();
        zoneId = destination;
    }

    /** Baja definitiva (la decide inventory; ver decisiones abiertas en la documentación). */
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
}
