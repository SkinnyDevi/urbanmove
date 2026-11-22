package com.urbanmove.availability.application.port;

import com.urbanmove.availability.application.ConcurrentUpdateException;
import com.urbanmove.availability.application.DuplicateEventException;
import com.urbanmove.availability.application.EventMetadata;
import com.urbanmove.availability.application.EventOutcome;
import com.urbanmove.availability.domain.VehicleAvailability;
import java.util.List;

/** Puerto de escritura. */
public interface VehicleWriter {
    void apply(List<VehicleChange> changes, EventMetadata event);

    void markProcessed(EventMetadata event, EventOutcome outcome);

    boolean registerIfAbsent(VehicleAvailability vehicle);
}
