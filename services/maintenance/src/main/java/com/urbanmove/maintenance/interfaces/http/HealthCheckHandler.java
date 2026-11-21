package com.urbanmove.maintenance.interfaces.http;

import com.urbanmove.maintenance.infrastructure.health.DependencyHealthCheck;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import io.javalin.http.HttpStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GET /health (AGENTS.md 6.3): 200 si todas las dependencias responden, 503 si no,
 * para que Docker Compose y ECS reinicien el contenedor. Formato: Health de common.yaml.
 */
public final class HealthCheckHandler implements Handler {

    public record HealthResponse(String status, String service, Map<String, String> checks) {
    }

    private final String service;
    private final List<DependencyHealthCheck> checks;

    public HealthCheckHandler(String service, List<DependencyHealthCheck> checks) {
        this.service = service;
        this.checks = List.copyOf(checks);
    }

    @Override
    public void handle(Context ctx) {
        Map<String, String> results = new LinkedHashMap<>();
        boolean allUp = true;
        for (DependencyHealthCheck check : checks) {
            boolean up = check.isUp();
            results.put(check.name(), up ? "UP" : "DOWN");
            if (check.critical()) {
                allUp &= up;
            }
        }
        ctx.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE)
                .json(new HealthResponse(allUp ? "UP" : "DOWN", service, results));
    }
}
