package com.urbanmove.inventory.application.port;

import com.urbanmove.inventory.domain.VehicleAsset;
import java.util.List;

/** Escritura atómica de una baja: activo, clave de idempotencia y evento pendiente juntos. */
public interface RetirementWriter {
    void retire(VehicleAsset asset, long expectedVersion, IdempotencyRecord idempotency, List<OutgoingEvent> events);
}
