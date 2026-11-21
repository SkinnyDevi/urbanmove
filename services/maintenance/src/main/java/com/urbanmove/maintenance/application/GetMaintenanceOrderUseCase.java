package com.urbanmove.maintenance.application;

import com.urbanmove.maintenance.application.port.MaintenanceOrderReader;
import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.MaintenanceOrder;
import java.util.Objects;

/** Caso de uso: obtener una orden por su id. */
public final class GetMaintenanceOrderUseCase {

    private final MaintenanceOrderReader reader;

    public GetMaintenanceOrderUseCase(MaintenanceOrderReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    /** @throws OrderNotFoundException si no existe */
    public MaintenanceOrder execute(MaintenanceOrderId id) {
        return reader.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }
}
