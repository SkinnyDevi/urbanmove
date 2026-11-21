package com.urbanmove.zones.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.zones.application.SeedZonesUseCase;
import com.urbanmove.zones.infrastructure.ServiceConfig;
import com.urbanmove.zones.infrastructure.persistence.Database;
import com.urbanmove.zones.infrastructure.persistence.JdbcZoneRepository;
import com.urbanmove.zones.infrastructure.seed.JsonZoneSeedSource;
import com.zaxxer.hikari.HikariDataSource;
import java.io.InputStream;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El servicio completo contra PostgreSQL real: semilla, seguridad, errores y correlationId. */
class ZonesApiIntegrationTest extends ZonesApiTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode body(HttpResponse<String> response) {
        try {
            return JSON.readTree(response.body());
        } catch (Exception e) {
            throw new AssertionError("Respuesta no es JSON: " + response.body(), e);
        }
    }

    private static int zonesInSeedFile() throws Exception {
        try (InputStream in = ZonesApiIntegrationTest.class.getClassLoader().getResourceAsStream("seed/zones.json")) {
            return JSON.readTree(in).size();
        }
    }

    private static boolean isProblemJson(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json");
    }

    @Test
    void healthIsPublicAndReportsTheDatabase() {
        HttpResponse<String> response = get("/health", null);

        assertEquals(200, response.statusCode());
        JsonNode health = body(response);
        assertEquals("UP", health.get("status").asText());
        assertEquals("zones-service", health.get("service").asText());
        assertEquals("UP", health.get("checks").get("database").asText());
    }

    @Test
    void theSeedIsLoadedOnStartup() throws Exception {
        HttpResponse<String> response = get("/zones", token("user"));

        assertEquals(200, response.statusCode());
        assertEquals(zonesInSeedFile(), body(response).get("items").size());
    }

    @Test
    void runningTheSeedAgainAgainstTheRealDatabaseInsertsNothing() {
        ServiceConfig config = databaseConfig();
        try (HikariDataSource db = Database.connect(config.dbUrl(), config.dbUser(), config.dbPassword())) {
            JdbcZoneRepository repository = new JdbcZoneRepository(db, JSON);
            SeedZonesUseCase seed = new SeedZonesUseCase(JsonZoneSeedSource.fromLocation(config.seedFile(), JSON), repository);

            assertEquals(0, seed.execute());
        }
    }

    @Test
    void getsOneZone() {
        HttpResponse<String> response = get("/zones/tafira", token("maintenance"));

        assertEquals(200, response.statusCode());
        JsonNode zone = body(response);
        assertEquals("tafira", zone.get("zoneId").asText());
        assertEquals("Campus de Tafira (ULPGC)", zone.get("name").asText());
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
        assertTrue(isProblemJson(response));
        JsonNode problem = body(response);
        assertEquals("/problems/not-found", problem.get("type").asText());
        assertEquals(404, problem.get("status").asInt());
        assertTrue(problem.hasNonNull("correlationId"));
    }

    @Test
    void aMalformedZoneIdIs400() {
        HttpResponse<String> response = get("/zones/Mesa_Y_Lopez", token("user"));

        assertEquals(400, response.statusCode());
        assertEquals("/problems/validation", body(response).get("type").asText());
    }

    @Test
    void anUnknownRouteIs404ProblemDetails() {
        HttpResponse<String> response = get("/no-such-route", token("user"));

        assertEquals(404, response.statusCode());
        assertTrue(isProblemJson(response));
        assertTrue(body(response).hasNonNull("correlationId"));
    }

    @Test
    void aWrongMethodIs405ProblemDetails() {
        HttpResponse<String> response = request("DELETE", "/zones/tafira", token("admin"), null);

        assertEquals(405, response.statusCode());
        assertTrue(isProblemJson(response));
    }

    @Test
    void withoutTokenIs401ProblemDetails() {
        HttpResponse<String> response = get("/zones", null);

        assertEquals(401, response.statusCode());
        assertTrue(isProblemJson(response));
        assertEquals("/problems/unauthorized", body(response).get("type").asText());
        assertTrue(body(response).hasNonNull("correlationId"));
    }

    @Test
    void anExpiredTokenIs401() {
        String expired = token(List.of("user"), Instant.now().minusSeconds(600));
        assertEquals(401, get("/zones", expired).statusCode());
    }

    @Test
    void aTokenWithABadSignatureIs401() {
        String token = token("admin");
        String forged = token.substring(0, token.lastIndexOf('.')) + ".invalidsignature";
        assertEquals(401, get("/zones", forged).statusCode());
    }

    @Test
    void aTokenWithoutAnOfficialRoleIs403() {
        HttpResponse<String> response = get("/zones", token("guest"));

        assertEquals(403, response.statusCode());
        assertEquals("/problems/forbidden", body(response).get("type").asText());
    }

    @Test
    void theCorrelationIdIsPropagatedToTheResponseAndToErrors() {
        String correlationId = UUID.randomUUID().toString();

        HttpResponse<String> ok = get("/zones/tafira", token("user"), correlationId);
        HttpResponse<String> error = get("/zones/atlantis", token("user"), correlationId);

        assertEquals(correlationId, ok.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertEquals(correlationId, error.headers().firstValue("X-Correlation-Id").orElseThrow());
        assertEquals(correlationId, body(error).get("correlationId").asText());
    }

    @Test
    void aCorrelationIdIsGeneratedWhenMissingOrMalformed() {
        String generated = get("/health", null).headers().firstValue("X-Correlation-Id").orElseThrow();
        String replaced = get("/health", null, "not-a-uuid").headers().firstValue("X-Correlation-Id").orElseThrow();

        assertDoesNotThrow(() -> UUID.fromString(generated));
        assertDoesNotThrow(() -> UUID.fromString(replaced));
    }
}
