package com.urbanmove.relocation.application;

/** Qué pasó con un DemandHotspotDetected recibido; en todos los casos se confirma el mensaje. */
public enum HotspotOutcome {
    CREATED,
    DUPLICATE,
    ACTIVE_ORDER_EXISTS,
    REJECTED
}
