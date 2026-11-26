package com.urbanmove.inventory.application;

import com.urbanmove.inventory.application.port.AssetPage;
import com.urbanmove.inventory.application.port.AssetSearch;
import com.urbanmove.inventory.application.port.VehicleAssetSearch;
import java.util.Objects;

/** Caso de uso: listar los activos de la flota con filtros. */
public final class ListVehiclesUseCase {
    private final VehicleAssetSearch search;

    public ListVehiclesUseCase(VehicleAssetSearch search) {
        this.search = Objects.requireNonNull(search);
    }

    public AssetPage execute(AssetSearch filters) {
        return search.search(filters);
    }
}
