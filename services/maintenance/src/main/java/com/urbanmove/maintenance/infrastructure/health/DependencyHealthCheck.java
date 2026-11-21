package com.urbanmove.maintenance.infrastructure.health;

/** Comprobación de una dependencia para GET /health (base de datos, bus de eventos...). */
public interface DependencyHealthCheck {

    /** Nombre que aparece en {@code checks} de la respuesta, p. ej. "dynamodb". */
    String name();

    /** true si la dependencia responde. No debe lanzar excepciones ni tardar más de ~3 s. */
    boolean isUp();

    /**
     * Si es crítica, que falle pone el servicio en 503 y ECS lo reinicia. Si no lo es, solo se
     * informa: reiniciar el servicio no arregla una caída de SNS (degradación elegante).
     */
    default boolean critical() {
        return true;
    }
}
