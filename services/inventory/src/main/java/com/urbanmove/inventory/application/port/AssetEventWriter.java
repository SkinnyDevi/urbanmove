package com.urbanmove.inventory.application.port;

import com.urbanmove.inventory.application.EventMetadata;
import com.urbanmove.inventory.application.EventOutcome;
import com.urbanmove.inventory.domain.VehicleAsset;

/** Escritura de lo que provoca un evento del bus, junto con la marca de evento procesado. */
public interface AssetEventWriter {
    void register(VehicleAsset asset, EventMetadata event);

    void update(VehicleAsset asset, long expectedVersion, EventMetadata event);

    void markProcessed(EventMetadata event, EventOutcome outcome);

    boolean registerIfAbsent(VehicleAsset asset);
}
