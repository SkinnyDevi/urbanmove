package com.urbanmove.maintenancedetection.application;

import com.urbanmove.maintenancedetection.domain.VehicleSignal;
import java.util.Objects;

/** Evento recibido del bus: su envelope y la señal que contiene. */
public record IncomingSignal(SignalMetadata metadata, VehicleSignal signal) {
    public IncomingSignal {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(signal);
    }
}
