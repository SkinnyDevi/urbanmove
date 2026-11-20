package com.urbanmove.zones.domain;

import java.util.List;
import java.util.Objects;

/**
 * Zona de la ciudad donde se pueden aparcar y recoger vehículos.
 *
 * <p>Es inmutable: el catálogo de zonas solo cambia al cargar la semilla. Las invariantes
 * se comprueban al construirla, así no puede existir una zona inválida en memoria.
 */
public record Zone(ZoneId id, String name, GeoPoint center, int radiusMeters, int capacity,
                   List<String> parkingRestrictions) {

    public Zone {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(center, "center");
        if (name == null || name.isBlank()) {
            throw new InvalidZoneException("Zone " + id + " has no name");
        }
        if (radiusMeters < 1) {
            throw new InvalidZoneException("Zone " + id + " must have a positive radius");
        }
        if (capacity < 0) {
            throw new InvalidZoneException("Zone " + id + " cannot have negative capacity");
        }
        parkingRestrictions = parkingRestrictions == null ? List.of() : List.copyOf(parkingRestrictions);
    }
}
