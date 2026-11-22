package com.urbanmove.zones.domain;

import java.util.List;
import java.util.Optional;

/** Puerto de lectura del catálogo de zonas. */
public interface ZoneReader {
    List<Zone> findAll();

    Optional<Zone> findById(ZoneId id);
}
