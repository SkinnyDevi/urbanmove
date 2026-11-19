package com.urbanmove.availability.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Identificador de vehículo con el formato de los contratos (common.schema.json: ^veh-[a-z0-9-]+$). */
public record VehicleId(String value) {

    private static final Pattern FORMAT = Pattern.compile("^veh-[a-z0-9-]+$");

    public VehicleId {
        Objects.requireNonNull(value, "vehicleId");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid vehicleId: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
