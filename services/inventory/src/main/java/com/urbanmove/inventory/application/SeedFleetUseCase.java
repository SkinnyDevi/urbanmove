package com.urbanmove.inventory.application;

import com.urbanmove.inventory.application.port.AssetEventWriter;
import com.urbanmove.inventory.application.port.FleetSeedSource;
import com.urbanmove.inventory.domain.VehicleAsset;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Caso de uso: cargar la flota inicial al arrancar sin pisar activos existentes. */
public final class SeedFleetUseCase {
    private static final Logger log = LoggerFactory.getLogger(SeedFleetUseCase.class);

    private final FleetSeedSource source;
    private final AssetEventWriter writer;
    private final Clock clock;

    public SeedFleetUseCase(FleetSeedSource source, AssetEventWriter writer, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.writer = Objects.requireNonNull(writer);
        this.clock = Objects.requireNonNull(clock);
    }

    public int execute() {
        List<InventoryEvent.Purchased> fleet = source.load();
        int created = 0;
        for (InventoryEvent.Purchased p : fleet) {
            if (writer.registerIfAbsent(VehicleAsset.registerPurchased(p.vehicleId(), p.vehicleType(), p.model(),
                    p.purchaseDate(), p.purchasePrice(), clock.instant()))) {
                created++;
            }
        }
        log.atInfo().addKeyValue("vehiclesInSeed", fleet.size()).addKeyValue("vehiclesCreated", created)
                .log("Fleet seed applied");
        return created;
    }
}
