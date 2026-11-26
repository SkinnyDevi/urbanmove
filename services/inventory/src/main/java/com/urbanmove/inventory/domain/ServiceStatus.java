package com.urbanmove.inventory.domain;

/** Si el activo está en la calle, retirado o dado de baja; IN_SERVICE agrupa los estados de availability. */
public enum ServiceStatus {
    IN_SERVICE,
    WITHDRAWN,
    RETIRED
}
