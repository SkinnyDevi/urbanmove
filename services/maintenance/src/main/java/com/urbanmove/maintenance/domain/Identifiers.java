package com.urbanmove.maintenance.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Value objects de identificadores con el formato de los contratos (common.schema.json).
 * Son propios de maintenance: no se importan de otros servicios (AGENTS.md 4.1).
 */
public final class Identifiers {

    private Identifiers() {
    }

    private static String check(String value, Pattern format, String name) {
        Objects.requireNonNull(value, name);
        if (!format.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
        return value;
    }

    public record MaintenanceOrderId(String value) {
        private static final Pattern FORMAT = Pattern.compile("^mnt-[a-z0-9-]+$");

        public MaintenanceOrderId {
            check(value, FORMAT, "maintenanceOrderId");
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

    /** Técnico de mantenimiento: es un usuario con rol maintenance. */
    public record TechnicianId(String value) {
        private static final Pattern FORMAT = Pattern.compile("^usr-[a-z0-9-]+$");

        public TechnicianId {
            check(value, FORMAT, "technicianId");
        }

        @Override
        public String toString() { return value; }
    }
}
