package com.urbanmove.relocation.application;

import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;

/** La orden de recolocación pedida no existe. */
public final class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(RelocationOrderId id) {
        super("Relocation order not found: " + id);
    }
}
