package com.urbanmove.zones.application;

import com.urbanmove.zones.domain.Zone;
import com.urbanmove.zones.domain.ZoneReader;
import java.util.List;
import java.util.Objects;

/** Caso de uso: listar todas las zonas. */
public final class ListZonesUseCase {

    private final ZoneReader zones;

    public ListZonesUseCase(ZoneReader zones) {
        this.zones = Objects.requireNonNull(zones);
    }

    public List<Zone> execute() {
        return zones.findAll();
    }
}
