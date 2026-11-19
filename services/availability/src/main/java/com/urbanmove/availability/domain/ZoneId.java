package com.urbanmove.availability.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Identificador de zona (slug) con el formato de los contratos (common.schema.json: ^[a-z0-9-]+$). */
public record ZoneId(String value) {

    private static final Pattern FORMAT = Pattern.compile("^[a-z0-9-]+$");

    public ZoneId {
        Objects.requireNonNull(value, "zoneId");
        if (!FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid zoneId: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
