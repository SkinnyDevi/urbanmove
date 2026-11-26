package com.urbanmove.relocation.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Identificadores con el formato de los contratos (common.schema.json). */
public final class Identifiers {

    private Identifiers() {
    }

    private static void check(String value, Pattern format, String name) {
        Objects.requireNonNull(value, name);
        if (!format.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
    }

    public record RelocationOrderId(String value) {
        private static final Pattern FORMAT = Pattern.compile("^rel-[a-z0-9-]+$");

        public RelocationOrderId {
            check(value, FORMAT, "relocationOrderId");
        }

        @Override
        public String toString() { return value; }
    }

    public record VehicleId(String value) {
        private static final Pattern FORMAT = Pattern.compile("^veh-[a-z0-9-]+$");

        public VehicleId {
            check(value, FORMAT, "vehicleId");
        }

        @Override
        public String toString() { return value; }
    }

    public record ZoneId(String value) {
        private static final Pattern FORMAT = Pattern.compile("^[a-z0-9-]+$");

        public ZoneId {
            check(value, FORMAT, "zoneId");
        }

        @Override
        public String toString() { return value; }
    }

    public record TechnicianId(String value) {
        private static final Pattern FORMAT = Pattern.compile("^usr-[a-z0-9-]+$");

        public TechnicianId {
            check(value, FORMAT, "technicianId");
        }

        @Override
        public String toString() { return value; }
    }
}
