package com.urbanmove.maintenance.application.port;

import java.util.List;

/**
 * Publica en el bus los eventos que ya están guardados como pendientes.
 *
 * <p>Es "mejor esfuerzo": si el bus falla, no lanza excepción. Los eventos siguen guardados y
 * un proceso periódico los reintenta, así que un fallo de SNS nunca hace fallar la petición del
 * técnico ni pierde el evento (AGENTS.md 6.4, degradación elegante).
 */
public interface EventDispatcher {

    void dispatch(List<OutgoingEvent> events);
}
