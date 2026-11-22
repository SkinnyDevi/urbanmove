package com.urbanmove.zones.application;

import com.urbanmove.zones.domain.Zone;
import com.urbanmove.zones.domain.ZoneId;
import com.urbanmove.zones.domain.ZoneReader;
import java.util.Objects;

/** Caso de uso: obtener una zona por su id. */
public final class GetZoneUseCase {
    private final ZoneReader zones;

    public GetZoneUseCase(ZoneReader zones) {
        this.zones = Objects.requireNonNull(zones);
    }

    public Zone execute(ZoneId id) {
        return zones.findById(id).orElseThrow(() -> new ZoneNotFoundException(id));
    }
}
