package com.urbanmove.relocation.application.port;

import com.urbanmove.relocation.domain.RelocationOrder;
import java.util.List;

/** Página de resultados con el cursor de la siguiente. */
public record OrderPage(List<RelocationOrder> items, String nextCursor) {
    public OrderPage {
        items = List.copyOf(items);
    }
}
