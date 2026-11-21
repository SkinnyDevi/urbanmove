package com.urbanmove.maintenance.application.port;

import com.urbanmove.maintenance.domain.Identifiers.TechnicianId;
import com.urbanmove.maintenance.domain.Identifiers.VehicleId;
import com.urbanmove.maintenance.domain.Identifiers.ZoneId;
import com.urbanmove.maintenance.domain.MaintenanceOrderStatus;
import java.util.EnumSet;
import java.util.Set;

/**
 * Filtros de GET /maintenance-orders. Un filtro null no filtra; {@code statuses} vacío = todos.
 * {@code cursor} es el {@code nextCursor} de la página anterior.
 */
public record OrderSearch(Set<MaintenanceOrderStatus> statuses, ZoneId zoneId, VehicleId vehicleId,
                          TechnicianId technicianId, int limit, String cursor) {

    public static final int MAX_LIMIT = 200;
    public static final int DEFAULT_LIMIT = 50;

    public OrderSearch {
        statuses = statuses == null || statuses.isEmpty()
                ? Set.copyOf(EnumSet.allOf(MaintenanceOrderStatus.class)) : Set.copyOf(statuses);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
