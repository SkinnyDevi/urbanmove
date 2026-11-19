package com.urbanmove.maintenance.domain;

/** Acciones que el técnico (o el sistema) puede pedir sobre una orden de mantenimiento. */
public enum MaintenanceAction {
    WITHDRAW_FOR_MAINTENANCE,
    START_CHARGING,
    START_REPAIR,
    MARK_READY_FOR_SERVICE,
    RETURN_TO_SERVICE,
    CANCEL
}
