package com.urbanmove.relocation.domain;

/** Estados de una orden de recolocación. */
public enum RelocationOrderStatus {
    CREATED,
    ASSIGNED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED
}
