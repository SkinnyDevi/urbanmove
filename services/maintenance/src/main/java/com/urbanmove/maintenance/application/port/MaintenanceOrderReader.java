package com.urbanmove.maintenance.application.port;

import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.MaintenanceOrder;
import java.util.Optional;

/** Puerto de lectura de órdenes. */
public interface MaintenanceOrderReader {
    Optional<MaintenanceOrder> findById(MaintenanceOrderId id);

    OrderPage search(OrderSearch search);
}
