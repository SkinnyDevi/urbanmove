package com.urbanmove.inventory.domain.event;

import java.time.Instant;

/** Evento de dominio registrado por un activo de la flota. */
public sealed interface DomainEvent permits VehicleRetired {

    String eventType();

    Instant occurredAt();
}
