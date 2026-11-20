package com.urbanmove.zones.infrastructure.health;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/** La base de datos responde a un SELECT 1 (con el timeout de consulta configurado). */
public final class DatabaseHealthCheck implements DependencyHealthCheck {

    private static final Logger log = LoggerFactory.getLogger(DatabaseHealthCheck.class);

    private final JdbcClient jdbc;

    public DatabaseHealthCheck(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public String name() {
        return "database";
    }

    @Override
    public boolean isUp() {
        try {
            return Integer.valueOf(1).equals(jdbc.sql("SELECT 1").query(Integer.class).single());
        } catch (RuntimeException e) {
            log.warn("Database health check failed: {}", e.getMessage());
            return false;
        }
    }
}
