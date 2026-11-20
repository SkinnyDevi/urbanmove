package com.urbanmove.zones.interfaces.rest;

import com.urbanmove.zones.infrastructure.health.DependencyHealthCheck;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /health (AGENTS.md 6.3): 200 si todas las dependencias responden, 503 si no,
 * para que Docker Compose y ECS reinicien el contenedor. Formato: Health de common.yaml.
 */
@RestController
public class HealthController {

    private final String service;
    private final List<DependencyHealthCheck> checks;

    public HealthController(@Value("${spring.application.name}") String service, List<DependencyHealthCheck> checks) {
        this.service = service;
        this.checks = checks;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        Map<String, String> results = new LinkedHashMap<>();
        boolean allUp = true;
        for (DependencyHealthCheck check : checks) {
            boolean up = check.isUp();
            results.put(check.name(), up ? "UP" : "DOWN");
            allUp &= up;
        }
        HealthResponse body = new HealthResponse(allUp ? "UP" : "DOWN", service, results);
        return ResponseEntity.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    public record HealthResponse(String status, String service, Map<String, String> checks) {
    }
}
