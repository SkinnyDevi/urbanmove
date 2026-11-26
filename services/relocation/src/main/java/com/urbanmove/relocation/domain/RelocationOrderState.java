package com.urbanmove.relocation.domain;

/** Patrón State de la orden de recolocación: cada estado decide qué acciones acepta. */
sealed interface RelocationOrderState {

    RelocationOrderStatus status();

    default RelocationOrderState assign() {
        throw illegal(RelocationAction.ASSIGN);
    }

    default RelocationOrderState start() {
        throw illegal(RelocationAction.START);
    }

    default RelocationOrderState complete() {
        throw illegal(RelocationAction.COMPLETE);
    }

    default RelocationOrderState cancel() {
        throw illegal(RelocationAction.CANCEL);
    }

    default boolean isActive() {
        return true;
    }

    private InvalidStateTransitionError illegal(RelocationAction action) {
        return new InvalidStateTransitionError(status(), action);
    }

    static RelocationOrderState of(RelocationOrderStatus status) {
        return switch (status) {
            case CREATED -> Created.INSTANCE;
            case ASSIGNED -> Assigned.INSTANCE;
            case IN_PROGRESS -> InProgress.INSTANCE;
            case COMPLETED -> Completed.INSTANCE;
            case CANCELLED -> Cancelled.INSTANCE;
        };
    }

    final class Created implements RelocationOrderState {
        static final Created INSTANCE = new Created();

        public RelocationOrderStatus status() { return RelocationOrderStatus.CREATED; }

        public RelocationOrderState assign() { return Assigned.INSTANCE; }

        public RelocationOrderState cancel() { return Cancelled.INSTANCE; }
    }

    final class Assigned implements RelocationOrderState {
        static final Assigned INSTANCE = new Assigned();

        public RelocationOrderStatus status() { return RelocationOrderStatus.ASSIGNED; }

        public RelocationOrderState start() { return InProgress.INSTANCE; }

        public RelocationOrderState cancel() { return Cancelled.INSTANCE; }
    }

    final class InProgress implements RelocationOrderState {
        static final InProgress INSTANCE = new InProgress();

        public RelocationOrderStatus status() { return RelocationOrderStatus.IN_PROGRESS; }

        public RelocationOrderState complete() { return Completed.INSTANCE; }
    }

    final class Completed implements RelocationOrderState {
        static final Completed INSTANCE = new Completed();

        public RelocationOrderStatus status() { return RelocationOrderStatus.COMPLETED; }

        public boolean isActive() { return false; }
    }

    final class Cancelled implements RelocationOrderState {
        static final Cancelled INSTANCE = new Cancelled();

        public RelocationOrderStatus status() { return RelocationOrderStatus.CANCELLED; }

        public boolean isActive() { return false; }
    }
}
