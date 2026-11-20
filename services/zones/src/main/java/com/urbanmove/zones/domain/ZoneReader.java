package com.urbanmove.zones.domain;

import java.util.List;
import java.util.Optional;

/** Puerto de lectura del catálogo de zonas (AGENTS.md 4.2, ISP: lectura y escritura separadas). */
public interface ZoneReader {

    /** Todas las zonas, ordenadas por id. */
    List<Zone> findAll();

    Optional<Zone> findById(ZoneId id);
}
