package com.urbanmove.availability.application.port;

import com.urbanmove.availability.application.VehicleEvent;
import java.util.List;

/** De dónde sale la flota inicial (vehicles.json del generador en la implementación actual). */
public interface FleetSeedSource {
    List<VehicleEvent.Purchased> load();
}
