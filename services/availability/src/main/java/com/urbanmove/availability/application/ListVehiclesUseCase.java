package com.urbanmove.availability.application;

import com.urbanmove.availability.application.port.VehiclePage;
import com.urbanmove.availability.application.port.VehicleReader;
import com.urbanmove.availability.application.port.VehicleSearch;
import java.util.Objects;

/** Caso de uso: vehículos por estado, zona y tipo (la app de usuario busca los AVAILABLE). */
public final class ListVehiclesUseCase {
    private final VehicleReader reader;

    public ListVehiclesUseCase(VehicleReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    public VehiclePage execute(VehicleSearch search) {
        return reader.search(search);
    }
}
