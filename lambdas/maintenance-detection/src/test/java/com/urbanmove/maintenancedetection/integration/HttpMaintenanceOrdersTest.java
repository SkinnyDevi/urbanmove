package com.urbanmove.maintenancedetection.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import com.urbanmove.maintenancedetection.application.MaintenanceUnavailableException;
import com.urbanmove.maintenancedetection.application.OrderRejectedException;
import com.urbanmove.maintenancedetection.application.SignalMetadata;
import com.urbanmove.maintenancedetection.application.port.OpenOutcome;
import com.urbanmove.maintenancedetection.domain.MaintenanceReason;
import com.urbanmove.maintenancedetection.domain.MaintenanceRequest;
import com.urbanmove.maintenancedetection.domain.VehicleId;
import com.urbanmove.maintenancedetection.domain.VehicleType;
import com.urbanmove.maintenancedetection.domain.ZoneId;
import com.urbanmove.maintenancedetection.infrastructure.http.HttpMaintenanceOrders;
import com.urbanmove.maintenancedetection.infrastructure.http.RetryPolicy;
import com.urbanmove.maintenancedetection.infrastructure.http.ServiceTokenIssuer;
import com.urbanmove.maintenancedetection.integration.FakeMaintenance.Reply;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** El adaptador HTTP contra un maintenance falso: respuestas, reintentos, timeout y cabeceras. */
class HttpMaintenanceOrdersTest {
    static final String SECRET = "test-only-secret-with-at-least-32-bytes!!";
    private static final MaintenanceRequest REQUEST = new MaintenanceRequest(new VehicleId("veh-0042"),
            VehicleType.SCOOTER, MaintenanceReason.LOW_BATTERY, new ZoneId("mesa-y-lopez"), "Batería al 12 %");

    private final FakeMaintenance maintenance = new FakeMaintenance();
    private final SignalMetadata trigger = new SignalMetadata(UUID.randomUUID(), "VehicleBatteryLow",
            UUID.randomUUID().toString());

    static HttpMaintenanceOrders client(FakeMaintenance maintenance, Duration timeout) {
        return new HttpMaintenanceOrders(HttpClient.newBuilder().connectTimeout(timeout).build(), maintenance.url(),
                timeout, new ServiceTokenIssuer(SECRET, "svc-maintenance-detection", Clock.systemUTC()),
                new RetryPolicy(3, Duration.ofMillis(10), Duration.ofMillis(50), bound -> bound), new ObjectMapper());
    }

    private OpenOutcome open() {
        return client(maintenance, Duration.ofMillis(500)).open(REQUEST, trigger);
    }

    @AfterEach
    void stop() {
        maintenance.close();
    }

    @Test
    void aCreatedOrderIsReported() throws Exception {
        assertEquals(OpenOutcome.CREATED, open());

        FakeMaintenance.Received request = maintenance.received().get(0);
        JsonNode body = new ObjectMapper().readTree(request.body());
        assertEquals("LOW_BATTERY", body.get("reason").asText());
        assertEquals(trigger.eventId().toString(), body.get("triggeredByEventId").asText());
        assertEquals(trigger.eventId().toString(), request.header("Idempotency-Key"), "el eventId hace la llamada idempotente");
        assertEquals(trigger.correlationId(), request.header("X-Correlation-Id"));
    }

    @Test
    void theServiceTokenIsSignedWithTheAdminRole() throws Exception {
        open();

        SignedJWT jwt = SignedJWT.parse(maintenance.received().get(0).header("Authorization").substring(7));
        assertTrue(jwt.verify(new MACVerifier(SECRET.getBytes(StandardCharsets.UTF_8))));
        assertEquals("svc-maintenance-detection", jwt.getJWTClaimsSet().getSubject());
        assertEquals(List.of("admin"), jwt.getJWTClaimsSet().getStringListClaim("roles"));
    }

    @Test
    void aRepeatedEventOrAnActiveOrderMeanTheOrderAlreadyExists() {
        maintenance.then(Reply.of(200, FakeMaintenance.ORDER),
                Reply.of(409, "{\"type\":\"/problems/active-order-exists\",\"status\":409}"));

        assertEquals(OpenOutcome.ALREADY_OPEN, open());
        assertEquals(OpenOutcome.ALREADY_OPEN, open());
    }

    @Test
    void aTemporaryFailureIsRetriedWithTheSameIdempotencyKey() {
        maintenance.then(Reply.of(503, ""), Reply.of(500, ""));

        assertEquals(OpenOutcome.CREATED, open());

        List<FakeMaintenance.Received> calls = maintenance.received();
        assertEquals(3, calls.size());
        assertEquals(1, calls.stream().map(c -> c.header("Idempotency-Key")).distinct().count());
    }

    @Test
    void maintenanceDownAfterAllAttemptsIsUnavailable() {
        maintenance.always(Reply.of(503, ""));

        assertThrows(MaintenanceUnavailableException.class, this::open);
        assertEquals(3, maintenance.received().size());
    }

    @Test
    void aSlowMaintenanceIsCutByTheTimeout() {
        maintenance.always(new Reply(201, FakeMaintenance.ORDER, 1500));
        long start = System.nanoTime();

        assertThrows(MaintenanceUnavailableException.class, this::open);

        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 3000, "3 intentos de 500 ms, no de 1,5 s: " + elapsedMillis);
    }

    @Test
    void anInvalidOrderIsRejectedWithoutRetrying() {
        maintenance.always(Reply.of(400, "{\"type\":\"/problems/validation\"}"));

        assertThrows(OrderRejectedException.class, this::open);
        assertEquals(1, maintenance.received().size());
    }

    @Test
    void aRefusedTokenIsUnavailableSoTheMessageCanBeReprocessedLater() {
        maintenance.always(Reply.of(401, ""));

        assertThrows(MaintenanceUnavailableException.class, this::open);
        assertEquals(1, maintenance.received().size());
    }

    @Test
    void anUnreachableMaintenanceIsUnavailable() {
        maintenance.close();

        assertThrows(MaintenanceUnavailableException.class, this::open);
    }
}
