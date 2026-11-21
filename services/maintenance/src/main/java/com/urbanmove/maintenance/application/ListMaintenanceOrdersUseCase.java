package com.urbanmove.maintenance.application;

import com.urbanmove.maintenance.application.port.MaintenanceOrderReader;
import com.urbanmove.maintenance.application.port.OrderPage;
import com.urbanmove.maintenance.application.port.OrderSearch;
import java.util.Objects;

/** Caso de uso: la bandeja de órdenes de la app de mantenimiento. */
public final class ListMaintenanceOrdersUseCase {

    private final MaintenanceOrderReader reader;

    public ListMaintenanceOrdersUseCase(MaintenanceOrderReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    public OrderPage execute(OrderSearch search) {
        return reader.search(search);
    }
}
