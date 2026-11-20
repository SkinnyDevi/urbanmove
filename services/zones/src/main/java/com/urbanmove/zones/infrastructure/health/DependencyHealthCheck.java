package com.urbanmove.zones.infrastructure.health;

/** Comprobación de una dependencia para GET /health (base de datos, bus de eventos...). */
public interface DependencyHealthCheck {

    /** Nombre que aparece en {@code checks} de la respuesta, p. ej. "database". */
    String name();

    /** true si la dependencia responde. No debe lanzar excepciones ni tardar más de ~3 s. */
    boolean isUp();
}
