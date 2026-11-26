package com.urbanmove.inventory.application.port;

import com.urbanmove.inventory.domain.AdminStatus;
import com.urbanmove.inventory.domain.VehicleType;

/** Filtros de GET /vehicles; null significa sin filtro. */
public record AssetSearch(VehicleType vehicleType, AdminStatus adminStatus, int limit, String cursor) {
    public static final int MAX_LIMIT = 200;
    public static final int DEFAULT_LIMIT = 50;

    public AssetSearch {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
