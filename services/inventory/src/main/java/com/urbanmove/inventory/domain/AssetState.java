package com.urbanmove.inventory.domain;

/** Patrón State: cada estado del activo decide qué acciones acepta y a qué estado lleva. */
sealed interface AssetState {
    ServiceStatus status();

    default AssetState withdraw() {
        throw illegal(AssetAction.WITHDRAW);
    }

    default AssetState returnToService() {
        throw illegal(AssetAction.RETURN_TO_SERVICE);
    }

    default AssetState retire() {
        throw illegal(AssetAction.RETIRE);
    }

    private InvalidStateTransitionError illegal(AssetAction action) {
        return new InvalidStateTransitionError(status(), action);
    }

    static AssetState of(ServiceStatus status) {
        return switch (status) {
            case IN_SERVICE -> InService.INSTANCE;
            case WITHDRAWN -> Withdrawn.INSTANCE;
            case RETIRED -> Retired.INSTANCE;
        };
    }

    final class InService implements AssetState {
        static final InService INSTANCE = new InService();

        public ServiceStatus status() { return ServiceStatus.IN_SERVICE; }

        public AssetState withdraw() { return Withdrawn.INSTANCE; }
    }

    final class Withdrawn implements AssetState {
        static final Withdrawn INSTANCE = new Withdrawn();

        public ServiceStatus status() { return ServiceStatus.WITHDRAWN; }

        public AssetState returnToService() { return InService.INSTANCE; }

        public AssetState retire() { return Retired.INSTANCE; }
    }

    final class Retired implements AssetState {
        static final Retired INSTANCE = new Retired();

        public ServiceStatus status() { return ServiceStatus.RETIRED; }
    }
}
