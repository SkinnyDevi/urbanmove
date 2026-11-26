package com.urbanmove.relocation.domain;

import com.urbanmove.relocation.domain.Identifiers.ZoneId;
import java.util.Objects;

/** Qué hay que mover: cuántos vehículos de un tipo, desde qué zona y hacia cuál. */
public record Route(ZoneId source, ZoneId target, VehicleType vehicleType, int quantity) {

    public Route {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(vehicleType, "vehicleType");
        if (source.equals(target)) {
            throw new InvalidRelocationException("Source and target zones must be different: " + source);
        }
        if (quantity < 1) {
            throw new InvalidRelocationException("Quantity must be at least 1");
        }
    }
}
