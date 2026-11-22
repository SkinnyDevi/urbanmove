package com.urbanmove.zones.application;

import com.urbanmove.zones.domain.Zone;
import com.urbanmove.zones.domain.ZoneSeedSource;
import com.urbanmove.zones.domain.ZoneWriter;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Caso de uso: cargar las zonas iniciales al arrancar. */
public final class SeedZonesUseCase {
    private static final Logger log = LoggerFactory.getLogger(SeedZonesUseCase.class);

    private final ZoneSeedSource source;
    private final ZoneWriter writer;

    public SeedZonesUseCase(ZoneSeedSource source, ZoneWriter writer) {
        this.source = Objects.requireNonNull(source);
        this.writer = Objects.requireNonNull(writer);
    }

    public int execute() {
        List<Zone> zones = source.load();
        int inserted = writer.saveIfAbsent(zones);
        log.atInfo()
                .addKeyValue("zonesInSeed", zones.size())
                .addKeyValue("zonesInserted", inserted)
                .log("Zone seed applied");
        return inserted;
    }
}
