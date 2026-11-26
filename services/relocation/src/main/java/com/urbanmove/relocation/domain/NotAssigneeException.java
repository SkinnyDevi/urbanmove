package com.urbanmove.relocation.domain;

/** Solo el técnico asignado puede empezar o completar la orden. */
public final class NotAssigneeException extends RuntimeException {
    public NotAssigneeException(Identifiers.RelocationOrderId orderId, Identifiers.TechnicianId technician) {
        super("Technician " + technician + " is not assigned to relocation order " + orderId);
    }
}
