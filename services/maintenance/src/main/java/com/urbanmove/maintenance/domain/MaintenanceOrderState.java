package com.urbanmove.maintenance.domain;

/**
 * Patrón State (AGENTS.md 5.3) para la orden de mantenimiento.
 *
 * <p>Por defecto toda acción es ilegal; cada estado sobrescribe solo las que admite.
 */
sealed interface MaintenanceOrderState {

    MaintenanceOrderStatus status();

    default MaintenanceOrderState withdrawForMaintenance() {
        throw illegal(MaintenanceAction.WITHDRAW_FOR_MAINTENANCE);
    }

    default MaintenanceOrderState startCharging() {
        throw illegal(MaintenanceAction.START_CHARGING);
    }

    default MaintenanceOrderState startRepair() {
        throw illegal(MaintenanceAction.START_REPAIR);
    }

    default MaintenanceOrderState markReadyForService() {
        throw illegal(MaintenanceAction.MARK_READY_FOR_SERVICE);
    }

    default MaintenanceOrderState returnToService() {
        throw illegal(MaintenanceAction.RETURN_TO_SERVICE);
    }

    default MaintenanceOrderState cancel() {
        throw illegal(MaintenanceAction.CANCEL);
    }

    private InvalidStateTransitionError illegal(MaintenanceAction action) {
        return new InvalidStateTransitionError(status(), action);
    }

    static MaintenanceOrderState of(MaintenanceOrderStatus status) {
        return switch (status) {
            case PENDING -> Pending.INSTANCE;
            case WITHDRAWN -> Withdrawn.INSTANCE;
            case CHARGING -> Charging.INSTANCE;
            case REPAIRING -> Repairing.INSTANCE;
            case READY_FOR_SERVICE -> ReadyForService.INSTANCE;
            case RETURNED -> Returned.INSTANCE;
            case CANCELLED -> Cancelled.INSTANCE;
        };
    }

    /** Orden creada (p. ej. por la Lambda maintenance-detection); el vehículo sigue en la calle. */
    final class Pending implements MaintenanceOrderState {
        static final Pending INSTANCE = new Pending();

        public MaintenanceOrderStatus status() { return MaintenanceOrderStatus.PENDING; }

        public MaintenanceOrderState withdrawForMaintenance() { return Withdrawn.INSTANCE; }

        public MaintenanceOrderState cancel() { return Cancelled.INSTANCE; }
    }

    final class Withdrawn implements MaintenanceOrderState {
        static final Withdrawn INSTANCE = new Withdrawn();

        public MaintenanceOrderStatus status() { return MaintenanceOrderStatus.WITHDRAWN; }

        public MaintenanceOrderState startCharging() { return Charging.INSTANCE; }

        public MaintenanceOrderState startRepair() { return Repairing.INSTANCE; }

        /** Solo revisión: no hacía falta cargar ni reparar. */
        public MaintenanceOrderState markReadyForService() { return ReadyForService.INSTANCE; }
    }

    final class Charging implements MaintenanceOrderState {
        static final Charging INSTANCE = new Charging();

        public MaintenanceOrderStatus status() { return MaintenanceOrderStatus.CHARGING; }

        /** Al cargarlo se descubre una avería. */
        public MaintenanceOrderState startRepair() { return Repairing.INSTANCE; }

        public MaintenanceOrderState markReadyForService() { return ReadyForService.INSTANCE; }
    }

    final class Repairing implements MaintenanceOrderState {
        static final Repairing INSTANCE = new Repairing();

        public MaintenanceOrderStatus status() { return MaintenanceOrderStatus.REPAIRING; }

        /** Tras la reparación hay que cargarlo. */
        public MaintenanceOrderState startCharging() { return Charging.INSTANCE; }

        public MaintenanceOrderState markReadyForService() { return ReadyForService.INSTANCE; }
    }

    final class ReadyForService implements MaintenanceOrderState {
        static final ReadyForService INSTANCE = new ReadyForService();

        public MaintenanceOrderStatus status() { return MaintenanceOrderStatus.READY_FOR_SERVICE; }

        public MaintenanceOrderState returnToService() { return Returned.INSTANCE; }
    }

    final class Returned implements MaintenanceOrderState {
        static final Returned INSTANCE = new Returned();

        public MaintenanceOrderStatus status() { return MaintenanceOrderStatus.RETURNED; }
    }

    final class Cancelled implements MaintenanceOrderState {
        static final Cancelled INSTANCE = new Cancelled();

        public MaintenanceOrderStatus status() { return MaintenanceOrderStatus.CANCELLED; }
    }
}
