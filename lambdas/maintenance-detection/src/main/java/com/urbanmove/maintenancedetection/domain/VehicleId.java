package com.urbanmove.maintenancedetection.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Identificador de un vehículo de la flota (veh-…). */
public record VehicleId(String value) {
    private static final Pattern FORMAT = Pattern.compile("^veh-[a-z0-9-]+$");

    public VehicleId {
        Objects.requireNonNull(value, "vehicleId");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("vehicleId must match veh-[a-z0-9-]+");
        }
    }
}
