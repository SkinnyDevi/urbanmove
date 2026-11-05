package com.urbanmove.synthgen;

/** Una zona de la ciudad tal como viene en config/zones.json. */
public record Zone(String zoneId, String name, double lat, double lon, int radiusMeters, int capacity,
                   String profile, double popularity) {
}
