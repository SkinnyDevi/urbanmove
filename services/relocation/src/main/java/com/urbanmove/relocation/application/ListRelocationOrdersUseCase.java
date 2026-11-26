package com.urbanmove.relocation.application;

import com.urbanmove.relocation.application.port.OrderPage;
import com.urbanmove.relocation.application.port.OrderSearch;
import com.urbanmove.relocation.application.port.RelocationOrderSearch;
import java.util.Objects;

/** Caso de uso: bandeja de órdenes de recolocación, las de plazo más próximo primero. */
public final class ListRelocationOrdersUseCase {
    private final RelocationOrderSearch search;

    public ListRelocationOrdersUseCase(RelocationOrderSearch search) {
        this.search = Objects.requireNonNull(search);
    }

    public OrderPage execute(OrderSearch filters) {
        return search.search(filters);
    }
}
