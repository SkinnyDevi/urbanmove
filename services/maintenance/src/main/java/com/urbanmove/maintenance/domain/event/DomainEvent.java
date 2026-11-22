package com.urbanmove.maintenance.domain.event;

import java.time.Instant;

/** Evento de dominio registrado por un agregado. */
public sealed interface DomainEvent permits VehicleWithdrawnForMaintenance, VehicleReturnedToService {
    String eventType();

    Instant occurredAt();
}
