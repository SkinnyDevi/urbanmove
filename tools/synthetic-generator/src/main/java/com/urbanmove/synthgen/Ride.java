package com.urbanmove.synthgen;

/** Un viaje en curso: lo que hace falta recordar entre RideStarted y RideFinished. */
public record Ride(String rideId, String originZoneId, String destinationZoneId, long startedAt, double distanceKm) {
}
