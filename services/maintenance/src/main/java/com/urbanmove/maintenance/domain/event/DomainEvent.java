package com.urbanmove.maintenance.domain.event;

import java.time.Instant;

/**
 * Evento de dominio registrado por un agregado (AGENTS.md 5.2).
 *
 * <p>Solo lleva los datos del payload y cuándo ocurrió. El envelope (eventId, source,
 * correlationId, schemaVersion) lo pone la infraestructura al publicar en SNS.
 */
public sealed interface DomainEvent permits VehicleWithdrawnForMaintenance, VehicleReturnedToService {

    /** Nombre del evento tal como aparece en el catálogo de docs/contracts/asyncapi/. */
    String eventType();

    Instant occurredAt();
}
