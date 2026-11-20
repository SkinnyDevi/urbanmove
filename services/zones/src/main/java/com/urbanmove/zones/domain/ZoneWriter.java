package com.urbanmove.zones.domain;

import java.util.Collection;

/** Puerto de escritura del catálogo de zonas. */
public interface ZoneWriter {

    /**
     * Guarda las zonas que aún no existen; las existentes no se tocan.
     * Es seguro llamarlo a la vez desde varias réplicas del servicio.
     *
     * @return cuántas zonas nuevas se han guardado
     */
    int saveIfAbsent(Collection<Zone> zones);
}
