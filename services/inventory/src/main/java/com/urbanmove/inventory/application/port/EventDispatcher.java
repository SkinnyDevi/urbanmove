package com.urbanmove.inventory.application.port;

import java.util.List;

/** Publica en el bus los eventos ya guardados; si falla, se reintentan más tarde. */
public interface EventDispatcher {
    void dispatch(List<OutgoingEvent> events);
}
