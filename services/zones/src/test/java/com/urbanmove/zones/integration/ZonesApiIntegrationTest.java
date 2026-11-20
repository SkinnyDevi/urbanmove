package com.urbanmove.zones.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.urbanmove.zones.application.SeedZonesUseCase;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** El servicio completo contra PostgreSQL real: semilla, seguridad, errores y correlationId. */
class ZonesApiIntegrationTest extends ZonesApiTestSupport {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private SeedZonesUseCase seedZones;

    private static JsonNode body(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static int zonesInSeedFile() throws Exception {
        try (var in = new ClassPathResource("seed/zones.json").getInputStream()) {
            return JSON.readTree(in).size();
        }
    }

    @Test
    void healthIsPublicAndReportsTheDatabase() {
        HttpResponse<String> response = get("/health", null);

        assertEquals(200, response.statusCode());
        JsonNode health = body(response);
        assertEquals("UP", health.get("status").asString());
        assertEquals("zones-service", health.get("service").asString());
        assertEquals("UP", health.get("checks").get("database").asString());
    }

    @Test
    void theSeedIsLoadedOnStartup() throws Exception {
        HttpResponse<String> response = get("/zones", token("user"));

        assertEquals(200, response.statusCode());
        assertEquals(zonesInSeedFile(), body(response).get("items").size());
    }

    @Test
    void runningTheSeedAgainInsertsNothing() {
        assertEquals(0, seedZones.execute());
    }

    @Test
    void getsOneZone() {
        HttpResponse<String> response = get("/zones/tafira", token("maintenance"));

        assertEquals(200, response.statusCode());
        JsonNode zone = body(response);
        assertEquals("tafira", zone.get("zoneId").asString());
        assertEquals("Campus de Tafira (ULPGC)", zone.get("name").asString());
        assertEquals(28.0716, zone.get("center").get("lat").asDouble());
    }

    @Test
    void everyOfficialRoleCanReadZones() {
        for (String role : List.of("user", "maintenance", "admin")) {
            assertEquals(200, get("/zones", token(role)).statusCode(), role);
        }
    }

    @Test
    void anUnknownZoneIs404ProblemDetails() {
        HttpResponse<String> response = get("/zones/atlantis", token("user"));

        assertEquals(404, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
        JsonNode problem = body(response);
        assertEquals("/problems/not-found", problem.get("type").asString());
        assertEquals(404, problem.get("status").asInt());
        assertTrue(problem.hasNonNull("correlationId"));
    }

    @Test
    void aMalformedZoneIdIs400() {
        HttpResponse<String> response = get("/zones/Mesa_Y_Lopez", token("user"));

        assertEquals(400, response.statusCode());
        assertEquals("/problems/validation", body(response).get("type").asString());
    }

    @Test
    void withoutTokenIs401ProblemDetails() {
        HttpResponse<String> response = get("/zones", null);

        assertEquals(401, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json"));
        assertEquals("/problems/unauthorized", body(response).get("type").asString());
        assertTrue(body(response).hasNonNull("correlationId"));
    }

    @Test
    void anExpiredTokenIs401() {
        String expired = token(List.of("user"), Instant.now().minusSeconds(600));
        assertEquals(401, get("/zones", expired).statusCode());
    }

    @Test
    void aTokenSignedWithAnotherSecretIs401() {
        String forged = token("admin").substring(0, token("admin").lastIndexOf('.')) + ".invalidsignature";
        assertEquals(401, get("/zones", forged).statusCode());
    }

    @Test
    void aTokenWithoutAnOfficialRoleIs403() {
        HttpResponse<String> response = get("/zones", token("guest"));

        assertEquals(403, response.statusCode());
        assertEquals("/problems/forbidden", body(response).get("type").asString());
    }

    @Test
    void theCorrelationIdIsPropagatedToTheResponseAndToErrors() {
        String correlationId = UUID.randomUUID().toString();

        HttpResponse<String> ok = get("/zones/tafira", token("user"), correlationId);
        HttpResponse<String> error = get("/zones/atlantis", token("user"), correlationId);

        assertEquals(correlationId, ok.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertEquals(correlationId, error.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertEquals(correlationId, body(error).get("correlationId").asString());
    }

    @Test
    void aCorrelationIdIsGeneratedWhenMissingOrMalformed() {
        String generated = get("/health", null).headers().firstValue("X-Correlation-Id").orElseThrow();
        String replaced = get("/health", null, "not-a-uuid").headers().firstValue("X-Correlation-Id").orElseThrow();

        assertDoesNotThrow(() -> UUID.fromString(generated));
        assertDoesNotThrow(() -> UUID.fromString(replaced));
    }
}
