package com.urbanmove.availability.application;

import com.urbanmove.availability.application.port.VehicleReader;
import com.urbanmove.availability.application.port.ZoneSummary;
import com.urbanmove.availability.domain.VehicleType;
import com.urbanmove.availability.domain.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Caso de uso: recuento de vehículos por zona, tipo y estado (app de mantenimiento y dashboard).
 */
public final class ZoneSummaryUseCase {
    private final VehicleReader reader;

    public ZoneSummaryUseCase(VehicleReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    public List<ZoneSummary> execute(ZoneId zoneId, VehicleType vehicleType) {
        return reader.summary(zoneId, vehicleType);
    }
}
