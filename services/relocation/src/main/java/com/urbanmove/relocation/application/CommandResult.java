package com.urbanmove.relocation.application;

import com.urbanmove.relocation.domain.RelocationOrder;

/** Resultado de una escritura; replayed indica que la clave ya se había usado. */
public record CommandResult(RelocationOrder order, boolean replayed) {
}
