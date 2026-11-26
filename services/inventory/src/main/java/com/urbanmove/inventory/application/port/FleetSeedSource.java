package com.urbanmove.inventory.application.port;

import com.urbanmove.inventory.application.InventoryEvent;
import java.util.List;

/** De dónde sale la flota inicial del entorno local. */
public interface FleetSeedSource {
    List<InventoryEvent.Purchased> load();
}
