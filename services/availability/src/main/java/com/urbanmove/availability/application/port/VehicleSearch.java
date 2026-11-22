package com.urbanmove.availability.application.port;

import com.urbanmove.availability.domain.VehicleStatus;
import com.urbanmove.availability.domain.VehicleType;
import com.urbanmove.availability.domain.ZoneId;
import java.util.Objects;

/** Filtros de GET /availability/vehicles. */
public record VehicleSearch(VehicleStatus status, ZoneId zoneId, VehicleType vehicleType, int limit, String cursor) {
    public static final int MAX_LIMIT = 200;
    public static final int DEFAULT_LIMIT = 50;

    public VehicleSearch {
        Objects.requireNonNull(status, "status");
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
