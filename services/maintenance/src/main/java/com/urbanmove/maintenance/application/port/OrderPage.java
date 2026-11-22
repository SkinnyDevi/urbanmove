package com.urbanmove.maintenance.application.port;

import com.urbanmove.maintenance.domain.MaintenanceOrder;
import java.util.List;

/** Página de resultados con el cursor de la siguiente. */
public record OrderPage(List<MaintenanceOrder> items, String nextCursor) {
    public OrderPage {
        items = List.copyOf(items);
    }
}
