package com.urbanmove.synthgen;

/** Comportamiento de un tipo de zona (config/demand_profiles.json). */
public record DemandProfile(double weekendFactor, double[] departures, double[] arrivals) {
}
