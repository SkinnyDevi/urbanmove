package com.urbanmove.inventory.domain;

/** Estado administrativo del activo: dado de alta o de baja definitiva. */
public enum AdminStatus {
    ACTIVE,
    RETIRED;

    public static AdminStatus of(ServiceStatus status) {
        return status == ServiceStatus.RETIRED ? RETIRED : ACTIVE;
    }
}
