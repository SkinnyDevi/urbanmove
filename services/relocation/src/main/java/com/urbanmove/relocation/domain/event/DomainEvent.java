package com.urbanmove.relocation.domain.event;

import java.time.Instant;

/** Evento de dominio registrado por una orden de recolocación. */
public sealed interface DomainEvent permits RelocationOrderCreated, RelocationOrderCompleted {

    String eventType();

    Instant occurredAt();
}
