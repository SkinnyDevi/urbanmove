package com.urbanmove.relocation.application.port;

import com.urbanmove.relocation.domain.RelocationOrder;
import java.util.List;

/** Puerto de escritura atómica: orden, clave de idempotencia y eventos pendientes juntos. */
public interface RelocationOrderWriter {
    void create(RelocationOrder order, IdempotencyRecord idempotency, List<OutgoingEvent> events);

    void update(RelocationOrder order, long expectedVersion, IdempotencyRecord idempotency, List<OutgoingEvent> events);
}
