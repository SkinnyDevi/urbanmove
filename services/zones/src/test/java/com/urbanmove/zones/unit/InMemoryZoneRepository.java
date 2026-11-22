package com.urbanmove.zones.unit;

import com.urbanmove.zones.domain.Zone;
import com.urbanmove.zones.domain.ZoneId;
import com.urbanmove.zones.domain.ZoneReader;
import com.urbanmove.zones.domain.ZoneWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Repositorio en memoria con el mismo comportamiento que JdbcZoneRepository. */
final class InMemoryZoneRepository implements ZoneReader, ZoneWriter {
    private final Map<ZoneId, Zone> zones = new ConcurrentHashMap<>();

    @Override
    public List<Zone> findAll() {
        List<Zone> all = new ArrayList<>(zones.values());
        all.sort(Comparator.comparing(z -> z.id().value()));
        return all;
    }

    @Override
    public Optional<Zone> findById(ZoneId id) {
        return Optional.ofNullable(zones.get(id));
    }

    @Override
    public int saveIfAbsent(Collection<Zone> toSave) {
        int inserted = 0;
        for (Zone zone : toSave) {
            if (zones.putIfAbsent(zone.id(), zone) == null) {
                inserted++;
            }
        }
        return inserted;
    }
}
