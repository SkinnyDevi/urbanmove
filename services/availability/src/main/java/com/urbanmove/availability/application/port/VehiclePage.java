package com.urbanmove.availability.application.port;

import com.urbanmove.availability.domain.VehicleAvailability;
import java.util.List;

/** Página de resultados con el cursor de la siguiente. */
public record VehiclePage(List<VehicleAvailability> items, String nextCursor) {
    public VehiclePage {
        items = List.copyOf(items);
    }
}
