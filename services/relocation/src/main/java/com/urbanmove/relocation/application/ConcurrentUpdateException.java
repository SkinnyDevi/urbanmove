package com.urbanmove.relocation.application;

import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;

/** Otra petición modificó la orden entre la lectura y la escritura. */
public final class ConcurrentUpdateException extends RuntimeException {
    public ConcurrentUpdateException(RelocationOrderId id) {
        super("Relocation order " + id + " was modified by another request; read it again and retry");
    }
}
