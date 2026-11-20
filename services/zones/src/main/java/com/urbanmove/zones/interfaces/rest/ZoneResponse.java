package com.urbanmove.zones.interfaces.rest;

import com.urbanmove.zones.domain.Zone;
import java.util.List;

/** DTO de salida: esquema Zone de docs/contracts/openapi/zones.yaml. */
public record ZoneResponse(String zoneId, String name, Center center, int radiusMeters, int capacity,
                           List<String> parkingRestrictions) {

    public record Center(double lat, double lon) {
    }

    public static ZoneResponse from(Zone zone) {
        return new ZoneResponse(
                zone.id().value(),
                zone.name(),
                new Center(zone.center().lat(), zone.center().lon()),
                zone.radiusMeters(),
                zone.capacity(),
                zone.parkingRestrictions());
    }
}
