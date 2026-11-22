package com.urbanmove.maintenance.infrastructure.health;

/** Comprobación de una dependencia para GET /health. */
public interface DependencyHealthCheck {
    String name();

    boolean isUp();

    default boolean critical() {
        return true;
    }
}
