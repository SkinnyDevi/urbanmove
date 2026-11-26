package com.urbanmove.maintenancedetection.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Identificador de una zona de la ciudad (slug). */
public record ZoneId(String value) {
    private static final Pattern FORMAT = Pattern.compile("^[a-z0-9-]+$");

    public ZoneId {
        Objects.requireNonNull(value, "zoneId");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("zoneId must match [a-z0-9-]+");
        }
    }
}
