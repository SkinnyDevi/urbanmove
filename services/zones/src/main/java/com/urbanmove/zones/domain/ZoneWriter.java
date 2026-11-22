package com.urbanmove.zones.domain;

import java.util.Collection;

/** Puerto de escritura del catálogo de zonas. */
public interface ZoneWriter {
    int saveIfAbsent(Collection<Zone> zones);
}
