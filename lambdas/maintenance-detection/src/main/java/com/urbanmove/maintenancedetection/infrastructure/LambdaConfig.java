package com.urbanmove.maintenancedetection.infrastructure;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Configuración de la Lambda, leída de variables de entorno. */
public record LambdaConfig(URI maintenanceUrl, String jwtSecret, Duration httpTimeout, int maxAttempts,
                           Duration safetyMargin) {
    public static final String SERVICE_SUBJECT = "svc-maintenance-detection";

    public LambdaConfig {
        Objects.requireNonNull(maintenanceUrl);
        Objects.requireNonNull(jwtSecret);
        Objects.requireNonNull(httpTimeout);
        Objects.requireNonNull(safetyMargin);
        if (httpTimeout.isZero() || httpTimeout.compareTo(Duration.ofSeconds(3)) > 0) {
            throw new IllegalArgumentException("HTTP_TIMEOUT_MS must be between 1 and 3000 (AGENTS.md 6.4)");
        }
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("MAX_ATTEMPTS must be between 1 and 5");
        }
    }

    public static LambdaConfig fromEnvironment(Map<String, String> env) {
        return new LambdaConfig(
                URI.create(required(env, "MAINTENANCE_URL")),
                required(env, "JWT_SECRET"),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("HTTP_TIMEOUT_MS", "3000"))),
                Integer.parseInt(env.getOrDefault("MAX_ATTEMPTS", "3")),
                Duration.ofMillis(Long.parseLong(env.getOrDefault("SAFETY_MARGIN_MS", "15000"))));
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing required environment variable " + name);
        }
        return value;
    }
}
