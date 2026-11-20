package com.urbanmove.zones.domain;

import java.util.List;

/** Puerto: de dónde salen las zonas iniciales (un fichero JSON en la implementación actual). */
public interface ZoneSeedSource {

    List<Zone> load();
}
