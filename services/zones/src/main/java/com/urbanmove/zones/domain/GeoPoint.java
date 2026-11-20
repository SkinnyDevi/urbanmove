package com.urbanmove.zones.domain;

/** Punto geográfico en grados decimales (common.schema.json: location). */
public record GeoPoint(double lat, double lon) {

    public GeoPoint {
        if (lat < -90 || lat > 90) {
            throw new InvalidZoneException("Latitude out of range: " + lat);
        }
        if (lon < -180 || lon > 180) {
            throw new InvalidZoneException("Longitude out of range: " + lon);
        }
    }
}
