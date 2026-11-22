package com.urbanmove.maintenance.application.port;

import com.urbanmove.maintenance.application.ActiveOrderExistsException;
import com.urbanmove.maintenance.application.ConcurrentUpdateException;
import com.urbanmove.maintenance.application.DuplicateRequestException;
import com.urbanmove.maintenance.domain.MaintenanceOrder;
import java.util.List;

/** Puerto de escritura de órdenes. */
public interface MaintenanceOrderWriter {
    void create(MaintenanceOrder order, IdempotencyRecord idempotency, List<OutgoingEvent> events);

    void update(MaintenanceOrder order, long expectedVersion, IdempotencyRecord idempotency, List<OutgoingEvent> events);
}
