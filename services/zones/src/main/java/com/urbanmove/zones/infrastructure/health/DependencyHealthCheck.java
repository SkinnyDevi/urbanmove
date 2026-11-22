package com.urbanmove.zones.infrastructure.health;

/** Comprobación de una dependencia para GET /health. */
public interface DependencyHealthCheck {
    String name();

    boolean isUp();
}
