package com.urbanmove.zones.domain;

/** Una zona o uno de sus datos no cumple las reglas del dominio. */
public final class InvalidZoneException extends RuntimeException {

    public InvalidZoneException(String message) {
        super(message);
    }
}
