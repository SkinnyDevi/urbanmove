package com.urbanmove.relocation.application.port;

/** Puerto de búsqueda paginada de órdenes. */
public interface RelocationOrderSearch {
    OrderPage search(OrderSearch search);
}
