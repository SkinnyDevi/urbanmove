package com.urbanmove.maintenance.application.port;

import com.urbanmove.maintenance.application.ActiveOrderExistsException;
import com.urbanmove.maintenance.application.ConcurrentUpdateException;
import com.urbanmove.maintenance.application.DuplicateRequestException;
import com.urbanmove.maintenance.domain.MaintenanceOrder;
import java.util.List;

/**
 * Puerto de escritura de órdenes. Cada operación es <b>atómica</b>: la orden, el registro de la
 * Idempotency-Key y los eventos pendientes de publicar se guardan juntos o no se guarda nada.
 * Así un evento nunca se pierde ni se publica sin que la orden haya cambiado (patrón outbox).
 */
public interface MaintenanceOrderWriter {

    /**
     * Guarda una orden nueva.
     *
     * @throws ActiveOrderExistsException si el vehículo ya tiene una orden activa
     * @throws DuplicateRequestException  si otra petición con la misma Idempotency-Key se guardó antes
     */
    void create(MaintenanceOrder order, IdempotencyRecord idempotency, List<OutgoingEvent> events);

    /**
     * Guarda los cambios de una orden existente, solo si nadie la ha modificado desde que se leyó.
     * Si la orden deja de estar activa, libera el vehículo para futuras órdenes.
     *
     * @throws ConcurrentUpdateException si la versión guardada ya no es {@code expectedVersion}
     * @throws DuplicateRequestException si otra petición con la misma Idempotency-Key se guardó antes
     */
    void update(MaintenanceOrder order, long expectedVersion, IdempotencyRecord idempotency, List<OutgoingEvent> events);
}
