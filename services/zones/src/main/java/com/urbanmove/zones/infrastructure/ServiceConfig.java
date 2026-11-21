package com.urbanmove.zones.infrastructure;

import java.util.Map;
import java.util.Objects;

/**
 * Configuración del servicio, leída de variables de entorno (AGENTS.md 8.5: nada sensible en el código).
 *
 * <p>Inmutable y creada una sola vez en el arranque: es el único tipo de "singleton" permitido
 * (AGENTS.md 5.1), y se pasa explícitamente a quien la necesite.
 */
public record ServiceConfig(String serviceName, int port, String dbUrl, String dbUser, String dbPassword,
                            String jwtSecret, String seedFile) {

    public ServiceConfig {
        Objects.requireNonNull(serviceName);
        Objects.requireNonNull(dbUrl);
        Objects.requireNonNull(dbUser);
        Objects.requireNonNull(dbPassword);
        Objects.requireNonNull(jwtSecret);
        Objects.requireNonNull(seedFile);
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
    }

    /**
     * Variables: PORT (8080), DB_URL, DB_USER, DB_PASSWORD (obligatoria), JWT_SECRET (obligatoria),
     * ZONES_SEED_FILE (por defecto la semilla incluida en el jar).
     */
    public static ServiceConfig fromEnvironment(Map<String, String> env) {
        return new ServiceConfig(
                "zones-service",
                Integer.parseInt(env.getOrDefault("PORT", "8080")),
                env.getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/zones"),
                env.getOrDefault("DB_USER", "zones"),
                required(env, "DB_PASSWORD"),
                required(env, "JWT_SECRET"),
                env.getOrDefault("ZONES_SEED_FILE", "classpath:seed/zones.json"));
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return value;
    }

    /** No imprime contraseñas ni secretos si alguien loguea la configuración. */
    @Override
    public String toString() {
        return "ServiceConfig[serviceName=" + serviceName + ", port=" + port + ", dbUrl=" + dbUrl
                + ", dbUser=" + dbUser + ", seedFile=" + seedFile + "]";
    }
}
