package com.urbanmove.availability.application.port;

import com.urbanmove.availability.domain.VehicleAvailability;
import com.urbanmove.availability.domain.VehicleId;
import com.urbanmove.availability.domain.VehicleType;
import com.urbanmove.availability.domain.ZoneId;
import java.util.List;
import java.util.Optional;

/** Puerto de lectura de la disponibilidad. */
public interface VehicleReader {
    Optional<VehicleAvailability> findById(VehicleId id);

    VehiclePage search(VehicleSearch search);

    List<ZoneSummary> summary(ZoneId zoneId, VehicleType vehicleType);
}
