package com.urbanmove.maintenance.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.urbanmove.maintenance.application.OrderActionCommand;
import com.urbanmove.maintenance.application.PerformOrderActionUseCase;
import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.Identifiers.TechnicianId;
import com.urbanmove.maintenance.domain.MaintenanceAction;
import com.urbanmove.maintenance.infrastructure.aws.AwsClients;
import com.urbanmove.maintenance.infrastructure.dynamo.DynamoMaintenanceRepository;
import com.urbanmove.maintenance.infrastructure.events.EventEnvelopes;
import com.urbanmove.maintenance.infrastructure.events.SnsOutboxPublisher;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sns.SnsClient;

/** El servicio completo contra DynamoDB, SNS y SQS reales (LocalStack). */
class MaintenanceApiIntegrationTest extends MaintenanceApiTestSupport {

    private static final String MAINTENANCE = token("maintenance");

    private static boolean isProblemJson(HttpResponse<String> response) {
        return response.headers().firstValue("Content-Type").orElse("").startsWith("application/problem+json");
    }

    @Test
    void healthReportsDynamoDbAndSns() {
        HttpResponse<String> response = get("/health", null);

        assertEquals(200, response.statusCode());
        JsonNode health = json(response);
        assertEquals("UP", health.get("status").asText());
        assertEquals("UP", health.get("checks").get("dynamodb").asText());
        assertEquals("UP", health.get("checks").get("sns").asText());
    }

    @Nested
    class MaintenanceFlow {

        /** El flujo de mantenimiento de la defensa (sección 11.2), de principio a fin. */
        @Test
        void fromOpeningToReturningPublishesBothEventsWithTheCorrelationId() {
            String vehicle = newVehicle();
            String correlationId = UUID.randomUUID().toString();

            HttpResponse<String> opened = request("POST", "/maintenance-orders", MAINTENANCE,
                    UUID.randomUUID().toString(), correlationId, openBody(vehicle));
            assertEquals(201, opened.statusCode(), opened.body());
            String id = json(opened).get("maintenanceOrderId").asText();
            assertEquals("/maintenance-orders/" + id, opened.headers().firstValue("Location").orElseThrow());
            assertEquals("PENDING", json(opened).get("status").asText());

            String base = "/maintenance-orders/" + id;
            assertEquals(200, request("POST", base + "/withdraw", MAINTENANCE, UUID.randomUUID().toString(),
                    correlationId, null).statusCode());
            assertEquals(200, request("POST", base + "/start-charging", MAINTENANCE, UUID.randomUUID().toString(),
                    correlationId, null).statusCode());
            assertEquals(200, request("POST", base + "/mark-ready", MAINTENANCE, UUID.randomUUID().toString(),
                    correlationId, null).statusCode());
            HttpResponse<String> returned = request("POST", base + "/return-to-service", MAINTENANCE,
                    UUID.randomUUID().toString(), correlationId, Map.of("zoneId", "tafira"));
            assertEquals(200, returned.statusCode(), returned.body());

            JsonNode order = json(returned);
            assertEquals("RETURNED", order.get("status").asText());
            assertEquals("usr-tech-it", order.get("technicianId").asText());
            assertEquals("tafira", order.get("returnZoneId").asText());
            assertEquals("RECHARGE", order.get("workPerformed").get(0).asText());

            List<JsonNode> events = awaitEvents(id, 2);
            assertEquals("VehicleWithdrawnForMaintenance", events.get(0).get("eventType").asText());
            assertEquals("VehicleReturnedToService", events.get(1).get("eventType").asText());
            for (JsonNode event : events) {
                assertEquals("urbanmove.maintenance", event.get("source").asText());
                assertEquals(correlationId, event.get("correlationId").asText(), "trazabilidad de extremo a extremo");
                assertEquals(vehicle, event.get("payload").get("vehicleId").asText());
            }
            assertEquals("usr-tech-it", events.get(0).get("payload").get("technicianId").asText());
            assertEquals("tafira", events.get(1).get("payload").get("zoneId").asText());
        }

        @Test
        void anIllegalTransitionIs409WithTheCurrentStateAndPublishesNothing() {
            String id = openOrder(newVehicle());

            HttpResponse<String> response = post("/maintenance-orders/" + id + "/start-charging", MAINTENANCE, null);

            assertEquals(409, response.statusCode());
            assertTrue(isProblemJson(response));
            JsonNode problem = json(response);
            assertEquals("/problems/invalid-state-transition", problem.get("type").asText());
            assertEquals("PENDING", problem.get("currentState").asText());
            assertEquals("START_CHARGING", problem.get("action").asText());
            assertTrue(eventsSoFar(id).isEmpty());
        }

        @Test
        void cancellingKeepsTheReasonAndFreesTheVehicle() {
            String vehicle = newVehicle();
            String id = openOrder(vehicle);

            HttpResponse<String> cancelled = post("/maintenance-orders/" + id + "/cancel", MAINTENANCE,
                    Map.of("reason", "Falsa alarma"));

            assertEquals(200, cancelled.statusCode());
            assertEquals("CANCELLED", json(cancelled).get("status").asText());
            assertEquals("Falsa alarma", json(cancelled).get("cancellationReason").asText());
            assertEquals(201, post("/maintenance-orders", MAINTENANCE, openBody(vehicle)).statusCode());
        }
    }

    @Nested
    class Idempotency {

        @Test
        void repeatingAnOpenWithTheSameKeyReturnsTheSameOrder() {
            String vehicle = newVehicle();
            String key = UUID.randomUUID().toString();

            HttpResponse<String> first = request("POST", "/maintenance-orders", MAINTENANCE, key, null, openBody(vehicle));
            HttpResponse<String> second = request("POST", "/maintenance-orders", MAINTENANCE, key, null, openBody(vehicle));

            assertEquals(201, first.statusCode());
            assertEquals(200, second.statusCode(), "la repetición no crea otra orden");
            assertEquals(json(first).get("maintenanceOrderId"), json(second).get("maintenanceOrderId"));
        }

        @Test
        void repeatingAWithdrawPublishesTheEventOnlyOnce() {
            String id = openOrder(newVehicle());
            String key = UUID.randomUUID().toString();

            HttpResponse<String> first = request("POST", "/maintenance-orders/" + id + "/withdraw", MAINTENANCE, key, null, null);
            HttpResponse<String> second = request("POST", "/maintenance-orders/" + id + "/withdraw", MAINTENANCE, key, null, null);

            assertEquals(200, first.statusCode());
            assertEquals(200, second.statusCode());
            awaitEvents(id, 1);
            assertEquals(1, eventsSoFar(id).size());
        }

        @Test
        void reusingAKeyForAnotherOperationIs409() {
            String id = openOrder(newVehicle());
            String key = UUID.randomUUID().toString();
            request("POST", "/maintenance-orders/" + id + "/withdraw", MAINTENANCE, key, null, null);

            HttpResponse<String> response = request("POST", "/maintenance-orders/" + id + "/start-repair",
                    MAINTENANCE, key, null, null);

            assertEquals(409, response.statusCode());
            assertEquals("/problems/idempotency-key-reused", json(response).get("type").asText());
        }

        /** Dos envíos idénticos a la vez (doble clic, reintento del cliente): una sola orden. */
        @Test
        void simultaneousIdenticalRequestsCreateOneOrder() throws Exception {
            String vehicle = newVehicle();
            String key = UUID.randomUUID().toString();

            List<HttpResponse<String>> responses = inParallel(8,
                    () -> request("POST", "/maintenance-orders", MAINTENANCE, key, null, openBody(vehicle)));

            assertTrue(responses.stream().allMatch(r -> r.statusCode() == 201 || r.statusCode() == 200),
                    () -> responses.stream().map(r -> r.statusCode() + " " + r.body()).toList().toString());
            assertEquals(1, responses.stream().map(r -> json(r).get("maintenanceOrderId").asText()).distinct().count());
        }
    }

    @Nested
    class OneActiveOrderPerVehicle {

        @Test
        void aSecondOrderForTheSameVehicleIs409WithTheActiveOne() {
            String vehicle = newVehicle();
            String first = openOrder(vehicle);

            HttpResponse<String> response = post("/maintenance-orders", MAINTENANCE, openBody(vehicle));

            assertEquals(409, response.statusCode());
            assertEquals("/problems/active-order-exists", json(response).get("type").asText());
            assertEquals(first, json(response).get("existingOrderId").asText());
        }

        /** Muchas órdenes distintas a la vez para el mismo vehículo: solo una gana (escritura condicional). */
        @Test
        void underConcurrencyOnlyOneOrderIsCreated() throws Exception {
            String vehicle = newVehicle();

            List<HttpResponse<String>> responses = inParallel(8,
                    () -> post("/maintenance-orders", MAINTENANCE, openBody(vehicle)));

            assertEquals(1, responses.stream().filter(r -> r.statusCode() == 201).count());
            assertEquals(7, responses.stream().filter(r -> r.statusCode() == 409).count());
        }
    }

    @Nested
    class Validation {

        @Test
        void theIdempotencyKeyIsRequired() {
            HttpResponse<String> response = request("POST", "/maintenance-orders", MAINTENANCE, null, null,
                    openBody(newVehicle()));

            assertEquals(400, response.statusCode());
            assertEquals("Idempotency-Key", json(response).get("errors").get(0).get("field").asText());
        }

        @Test
        void allBodyErrorsAreReportedTogether() {
            HttpResponse<String> response = post("/maintenance-orders", MAINTENANCE,
                    Map.of("vehicleId", "0042", "vehicleType", "TRUCK", "reason", "LOW_BATTERY",
                            "zoneId", "mesa-y-lopez", "colour", "red"));

            assertEquals(400, response.statusCode());
            assertTrue(isProblemJson(response));
            List<String> fields = new ArrayList<>();
            json(response).get("errors").forEach(e -> fields.add(e.get("field").asText()));
            assertTrue(fields.containsAll(List.of("vehicleId", "vehicleType", "colour")), fields.toString());
        }

        @Test
        void malformedJsonIs400() {
            assertEquals(400, post("/maintenance-orders", MAINTENANCE, "{not json").statusCode());
        }

        @Test
        void unknownAndMalformedOrderIds() {
            assertEquals(404, get("/maintenance-orders/mnt-does-not-exist", MAINTENANCE).statusCode());
            assertEquals(400, get("/maintenance-orders/ORDER-1", MAINTENANCE).statusCode());
        }
    }

    @Nested
    class Security {

        @Test
        void withoutTokenIs401() {
            HttpResponse<String> response = get("/maintenance-orders", null);

            assertEquals(401, response.statusCode());
            assertTrue(json(response).hasNonNull("correlationId"));
        }

        @Test
        void anEndUserCannotSeeMaintenanceOrders() {
            assertEquals(403, get("/maintenance-orders", token("user")).statusCode());
        }

        @Test
        void onlyTechniciansCanWithdrawVehicles() {
            String id = openOrder(newVehicle());

            assertEquals(403, post("/maintenance-orders/" + id + "/withdraw", token("admin"), null).statusCode());
        }

        @Test
        void anExpiredTokenIs401() {
            String expired = token(List.of("maintenance"), "usr-tech-it", Instant.now().minusSeconds(60));
            assertEquals(401, get("/maintenance-orders", expired).statusCode());
        }
    }

    @Nested
    class Listing {

        @Test
        void filtersByVehicleAndStatus() {
            String vehicle = newVehicle();
            String id = openOrder(vehicle);
            post("/maintenance-orders/" + id + "/withdraw", MAINTENANCE, null);

            JsonNode withdrawn = json(get("/maintenance-orders?status=WITHDRAWN&vehicleId=" + vehicle, MAINTENANCE));
            JsonNode pending = json(get("/maintenance-orders?status=PENDING&vehicleId=" + vehicle, MAINTENANCE));

            assertEquals(1, withdrawn.get("items").size());
            assertEquals(id, withdrawn.get("items").get(0).get("maintenanceOrderId").asText());
            assertEquals(0, pending.get("items").size());
        }

        @Test
        void paginatesWithACursor() {
            String zone = "zona-" + UUID.randomUUID().toString().substring(0, 6);
            List<String> created = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Map<String, Object> body = Map.of("vehicleId", newVehicle(), "vehicleType", "BIKE",
                        "reason", "INSPECTION", "zoneId", zone);
                created.add(json(post("/maintenance-orders", MAINTENANCE, body)).get("maintenanceOrderId").asText());
            }

            List<String> seen = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                String url = "/maintenance-orders?zoneId=" + zone + "&limit=1" + (cursor == null ? "" : "&cursor=" + cursor);
                JsonNode page = json(get(url, MAINTENANCE));
                page.get("items").forEach(item -> seen.add(item.get("maintenanceOrderId").asText()));
                cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
                pages++;
            } while (cursor != null && pages < 10);

            assertEquals(created, seen, "todas, una por página, de la más antigua a la más reciente");
        }

        @Test
        void anInvalidCursorIs400() {
            assertEquals(400, get("/maintenance-orders?cursor=not-a-cursor", MAINTENANCE).statusCode());
        }
    }

    /**
     * Tolerancia a fallos: si SNS falla al publicar, la orden se guarda igual y el evento queda en
     * la tabla; el reintento periódico lo publica después. No se pierde ningún evento.
     */
    @Test
    void anEventThatCouldNotBePublishedIsRelayedLater() {
        String id = openOrder(newVehicle());
        SdkHttpClient http = AwsClients.httpClient();
        try (DynamoDbClient dynamo = AwsClients.dynamoDb(CONFIG, http); SnsClient sns = AwsClients.sns(CONFIG, http)) {
            DynamoMaintenanceRepository repository =
                    new DynamoMaintenanceRepository(dynamo, TABLE, new EventEnvelopes(JSON), Clock.systemUTC());
            String missingTopic = TOPIC_ARN.replace("urbanmove-domain-events", "topic-that-does-not-exist");
            SnsOutboxPublisher brokenBus = new SnsOutboxPublisher(dynamo, sns, TABLE, missingTopic);
            PerformOrderActionUseCase withBrokenBus = new PerformOrderActionUseCase(repository, repository,
                    repository, brokenBus, UUID::randomUUID, Clock.systemUTC());

            var result = withBrokenBus.execute(new OrderActionCommand(UUID.randomUUID().toString(),
                    UUID.randomUUID().toString(), new MaintenanceOrderId(id),
                    MaintenanceAction.WITHDRAW_FOR_MAINTENANCE, new TechnicianId("usr-tech-it"), null, null));

            assertEquals("WITHDRAWN", result.order().status().name(), "la orden se guarda aunque el bus falle");
            assertEquals(0, brokenBus.relayPending(), "con el bus caído no se publica nada...");
            assertTrue(eventsSoFar(id).isEmpty(), "...y el evento sigue guardado, pendiente");
        } finally {
            http.close();
        }

        assertTrue(APP.relayPendingEvents() >= 1, "cuando el bus vuelve, el reintento lo publica");

        List<JsonNode> events = awaitEvents(id, 1);
        assertEquals("VehicleWithdrawnForMaintenance", events.get(0).get("eventType").asText());
    }

    private static List<HttpResponse<String>> inParallel(int times, Callable<HttpResponse<String>> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(times);
        try {
            List<Future<HttpResponse<String>>> futures = new ArrayList<>();
            for (int i = 0; i < times; i++) {
                futures.add(pool.submit(call));
            }
            List<HttpResponse<String>> responses = new ArrayList<>();
            for (Future<HttpResponse<String>> future : futures) {
                responses.add(future.get());
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }
}
