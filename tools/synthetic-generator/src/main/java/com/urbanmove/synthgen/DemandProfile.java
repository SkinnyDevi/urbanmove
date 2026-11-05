package com.urbanmove.synthgen;

/**
 * Comportamiento de un tipo de zona (config/demand_profiles.json). Cada lista tiene 24 valores, uno por hora.
 *
 * @param departures    ganas de COGER un vehículo en la zona a cada hora
 * @param arrivals      ganas de DEJAR un vehículo en la zona a cada hora
 * @param weekendFactor multiplica la demanda los sábados y domingos
 */
public record DemandProfile(double weekendFactor, double[] departures, double[] arrivals) {
}
