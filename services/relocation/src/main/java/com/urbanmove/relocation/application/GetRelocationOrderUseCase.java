package com.urbanmove.relocation.application;

import com.urbanmove.relocation.application.port.RelocationOrderReader;
import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.RelocationOrder;
import java.util.Objects;

/** Caso de uso: obtener una orden de recolocación por su id. */
public final class GetRelocationOrderUseCase {
    private final RelocationOrderReader reader;

    public GetRelocationOrderUseCase(RelocationOrderReader reader) {
        this.reader = Objects.requireNonNull(reader);
    }

    public RelocationOrder execute(RelocationOrderId id) {
        return reader.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
    }
}
