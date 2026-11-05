package com.urbanmove.synthgen;

import java.nio.file.Path;
import java.time.LocalDate;

/**
 * Parámetros que se pueden cambiar desde la línea de comandos (días, semilla, tamaño de la flota...).
 * Es inmutable: los métodos with...() devuelven una copia con un valor cambiado.
 *
 * @param startDate      primer día simulado; el 9 de noviembre de 2026 es lunes. En noviembre Canarias
 *                       usa UTC+0, así que la hora UTC de los eventos coincide con la hora local.
 * @param seed           misma semilla = exactamente los mismos datos
 * @param tripsPerDay    peticiones de vehículo esperadas en un día laborable
 * @param newUsersPerDay altas nuevas de media cada día durante la simulación
 * @param validate       validar cada evento contra los contratos
 */
public record Settings(LocalDate startDate, int days, long seed, int vehicles, int users, int technicians,
                       int tripsPerDay, double newUsersPerDay, boolean validate,
                       Path zonesFile, Path profilesFile) {

    public static Settings defaults() {
        Path config = ToolPaths.configDir();
        return new Settings(LocalDate.of(2026, 11, 9), 7, 42, 100, 200, 3, 700, 4.0, true,
                config.resolve("zones.json"), config.resolve("demand_profiles.json"));
    }

    public Settings withStartDate(LocalDate value) {
        return new Settings(value, days, seed, vehicles, users, technicians, tripsPerDay, newUsersPerDay, validate,
                zonesFile, profilesFile);
    }

    public Settings withDays(int value) {
        return new Settings(startDate, value, seed, vehicles, users, technicians, tripsPerDay, newUsersPerDay,
                validate, zonesFile, profilesFile);
    }

    public Settings withSeed(long value) {
        return new Settings(startDate, days, value, vehicles, users, technicians, tripsPerDay, newUsersPerDay,
                validate, zonesFile, profilesFile);
    }

    public Settings withVehicles(int value) {
        return new Settings(startDate, days, seed, value, users, technicians, tripsPerDay, newUsersPerDay, validate,
                zonesFile, profilesFile);
    }

    public Settings withUsers(int value) {
        return new Settings(startDate, days, seed, vehicles, value, technicians, tripsPerDay, newUsersPerDay,
                validate, zonesFile, profilesFile);
    }

    public Settings withTripsPerDay(int value) {
        return new Settings(startDate, days, seed, vehicles, users, technicians, value, newUsersPerDay, validate,
                zonesFile, profilesFile);
    }

    public Settings withValidate(boolean value) {
        return new Settings(startDate, days, seed, vehicles, users, technicians, tripsPerDay, newUsersPerDay, value,
                zonesFile, profilesFile);
    }
}
