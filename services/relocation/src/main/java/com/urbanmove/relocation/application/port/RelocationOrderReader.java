package com.urbanmove.relocation.application.port;

import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.RelocationOrder;
import java.util.Optional;

/** Puerto de lectura de una orden por su id. */
public interface RelocationOrderReader {
    Optional<RelocationOrder> findById(RelocationOrderId id);
}
