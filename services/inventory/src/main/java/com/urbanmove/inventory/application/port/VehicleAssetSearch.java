package com.urbanmove.inventory.application.port;

/** Puerto de búsqueda paginada de activos. */
public interface VehicleAssetSearch {
    AssetPage search(AssetSearch search);
}
