package com.urbanmove.inventory.application.port;

import com.urbanmove.inventory.domain.VehicleAsset;
import java.util.List;

/** Página de resultados con el cursor de la siguiente. */
public record AssetPage(List<VehicleAsset> items, String nextCursor) {
    public AssetPage {
        items = List.copyOf(items);
    }
}
