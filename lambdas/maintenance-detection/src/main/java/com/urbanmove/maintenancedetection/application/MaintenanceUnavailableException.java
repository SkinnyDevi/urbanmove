package com.urbanmove.maintenancedetection.application;

/** maintenance no respondió bien tras los reintentos: el mensaje se devuelve a la cola. */
public final class MaintenanceUnavailableException extends RuntimeException {
    public MaintenanceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
