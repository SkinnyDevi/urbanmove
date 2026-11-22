package com.urbanmove.availability.application;

import com.urbanmove.availability.application.port.FleetSeedSource;
import com.urbanmove.availability.application.port.VehicleWriter;
import com.urbanmove.availability.domain.VehicleAvailability;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Caso de uso: cargar la flota inicial al arrancar sin pisar vehículos existentes. */
public final class SeedFleetUseCase {
    private static final Logger log = LoggerFactory.getLogger(SeedFleetUseCase.class);

    private final FleetSeedSource source;
    private final VehicleWriter writer;
    private final Clock clock;

    public SeedFleetUseCase(FleetSeedSource source, VehicleWriter writer, Clock clock) {
        this.source = Objects.requireNonNull(source);
        this.writer = Objects.requireNonNull(writer);
        this.clock = Objects.requireNonNull(clock);
    }

    public int execute() {
        List<VehicleEvent.Purchased> fleet = source.load();
        int created = 0;
        for (VehicleEvent.Purchased vehicle : fleet) {
            if (writer.registerIfAbsent(VehicleAvailability.registerPurchased(vehicle.vehicleId(),
                    vehicle.vehicleType(), vehicle.initialZone(), clock.instant()))) {
                created++;
            }
        }
        log.atInfo().addKeyValue("vehiclesInSeed", fleet.size()).addKeyValue("vehiclesCreated", created)
                .log("Fleet seed applied");
        return created;
    }
}
