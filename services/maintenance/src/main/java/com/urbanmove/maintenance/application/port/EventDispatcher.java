package com.urbanmove.maintenance.application.port;

import java.util.List;

/** Publica en el bus los eventos que ya están guardados como pendientes. */
public interface EventDispatcher {
    void dispatch(List<OutgoingEvent> events);
}
