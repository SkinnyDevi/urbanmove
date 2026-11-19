package com.urbanmove.availability.domain;

/**
 * Patrón State (AGENTS.md 5.3): cada estado decide qué acciones acepta y a qué estado lleva.
 *
 * <p>Por defecto toda acción es ilegal y lanza {@link InvalidStateTransitionError}; cada estado
 * sobrescribe solo las que admite. Así la matriz de transiciones está en un único sitio y
 * no hay switch/if repartidos por los servicios. Los estados no guardan datos, por eso cada
 * uno es una única instancia inmutable.
 *
 * <p>{@code batteryLow} indica si telemetry avisó de batería baja mientras el vehículo estaba
 * reservado o en uso: al liberarse no vuelve a AVAILABLE sino a LOW_BATTERY.
 */
sealed interface VehicleState {

    VehicleStatus status();

    default VehicleState reserve() {
        throw illegal(VehicleAction.RESERVE);
    }

    default VehicleState releaseReservation(boolean batteryLow) {
        throw illegal(VehicleAction.RELEASE_RESERVATION);
    }

    default VehicleState startRide() {
        throw illegal(VehicleAction.START_RIDE);
    }

    default VehicleState finishRide(boolean batteryLow) {
        throw illegal(VehicleAction.FINISH_RIDE);
    }

    /** Batería baja: por defecto el estado no cambia (la telemetría se repite y no debe fallar). */
    default VehicleState reportLowBattery() {
        return this;
    }

    default VehicleState withdrawForMaintenance() {
        throw illegal(VehicleAction.WITHDRAW_FOR_MAINTENANCE);
    }

    default VehicleState returnToService() {
        throw illegal(VehicleAction.RETURN_TO_SERVICE);
    }

    /** Cambio de zona por una orden de recolocación completada; el estado no cambia. */
    default VehicleState relocate() {
        throw illegal(VehicleAction.RELOCATE);
    }

    default VehicleState retire() {
        throw illegal(VehicleAction.RETIRE);
    }

    private InvalidStateTransitionError illegal(VehicleAction action) {
        return new InvalidStateTransitionError(status(), action);
    }

    static VehicleState of(VehicleStatus status) {
        return switch (status) {
            case AVAILABLE -> Available.INSTANCE;
            case RESERVED -> Reserved.INSTANCE;
            case IN_USE -> InUse.INSTANCE;
            case LOW_BATTERY -> LowBattery.INSTANCE;
            case WITHDRAWN -> Withdrawn.INSTANCE;
            case RETIRED -> Retired.INSTANCE;
        };
    }

    final class Available implements VehicleState {
        static final Available INSTANCE = new Available();

        public VehicleStatus status() { return VehicleStatus.AVAILABLE; }

        public VehicleState reserve() { return Reserved.INSTANCE; }

        /** Uso directo sin reserva previa ("available for reservation or public use"). */
        public VehicleState startRide() { return InUse.INSTANCE; }

        public VehicleState reportLowBattery() { return LowBattery.INSTANCE; }

        public VehicleState withdrawForMaintenance() { return Withdrawn.INSTANCE; }

        public VehicleState relocate() { return this; }
    }

    final class Reserved implements VehicleState {
        static final Reserved INSTANCE = new Reserved();

        public VehicleStatus status() { return VehicleStatus.RESERVED; }

        public VehicleState releaseReservation(boolean batteryLow) {
            return batteryLow ? LowBattery.INSTANCE : Available.INSTANCE;
        }

        public VehicleState startRide() { return InUse.INSTANCE; }
    }

    final class InUse implements VehicleState {
        static final InUse INSTANCE = new InUse();

        public VehicleStatus status() { return VehicleStatus.IN_USE; }

        public VehicleState finishRide(boolean batteryLow) {
            return batteryLow ? LowBattery.INSTANCE : Available.INSTANCE;
        }
    }

    final class LowBattery implements VehicleState {
        static final LowBattery INSTANCE = new LowBattery();

        public VehicleStatus status() { return VehicleStatus.LOW_BATTERY; }

        public VehicleState withdrawForMaintenance() { return Withdrawn.INSTANCE; }

        public VehicleState relocate() { return this; }
    }

    final class Withdrawn implements VehicleState {
        static final Withdrawn INSTANCE = new Withdrawn();

        public VehicleStatus status() { return VehicleStatus.WITHDRAWN; }

        public VehicleState returnToService() { return Available.INSTANCE; }

        public VehicleState retire() { return Retired.INSTANCE; }
    }

    final class Retired implements VehicleState {
        static final Retired INSTANCE = new Retired();

        public VehicleStatus status() { return VehicleStatus.RETIRED; }
    }
}
