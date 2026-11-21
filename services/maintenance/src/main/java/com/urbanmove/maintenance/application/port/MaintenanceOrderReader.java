package com.urbanmove.maintenance.application.port;

import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.MaintenanceOrder;
import java.util.Optional;

/** Puerto de lectura de órdenes (AGENTS.md 4.2, ISP: lectura y escritura separadas). */
public interface MaintenanceOrderReader {

    Optional<MaintenanceOrder> findById(MaintenanceOrderId id);

    /** Órdenes que cumplen los filtros, las más antiguas primero, paginadas. */
    OrderPage search(OrderSearch search);
}
