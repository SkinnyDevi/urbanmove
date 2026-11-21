package com.urbanmove.maintenance.application.port;

import com.urbanmove.maintenance.domain.MaintenanceOrder;
import java.util.List;

/** Una página de resultados; {@code nextCursor} es null si no hay más. */
public record OrderPage(List<MaintenanceOrder> items, String nextCursor) {

    public OrderPage {
        items = List.copyOf(items);
    }
}
