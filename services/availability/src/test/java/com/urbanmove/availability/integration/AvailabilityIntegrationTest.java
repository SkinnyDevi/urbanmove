package com.urbanmove.availability.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** El servicio completo contra DynamoDB, SNS y SQS en LocalStack. */
class AvailabilityIntegrationTest extends AvailabilityTestSupport {
    private static final String STAFF = token("maintenance");

    @Test
    void healthReportsDynamoDbAndSqs() {
        JsonNode health = json(get("/health", null));

        assertEquals("UP", health.get("status").asText());
        assertEquals("UP", health.get("checks").get("dynamodb").asText());
        assertEquals("UP", health.get("checks").get("sqs").asText());
    }

    @Test
    void theInitialFleetIsLoadedFromTheGeneratorSeed() {
        JsonNode vehicle = json(get("/availability/vehicles/veh-0004", STAFF));

        assertEquals("AVAILABLE", vehicle.get("status").asText());
        assertEquals("las-canteras", vehicle.get("zoneId").asText());
        assertTrue(vehicle.get("reservable").asBoolean());
    }

    @Nested
    class EventsChangeTheAvailability {
        @Test
        void aPurchasedVehicleAppearsAvailableInItsInitialZone() {
            String id = purchase(newVehicle(), "triana");

            JsonNode vehicle = json(get("/availability/vehicles/" + id, STAFF));
            assertEquals("triana", vehicle.get("zoneId").asText());
            assertEquals("SCOOTER", vehicle.get("vehicleType").asText(), "el tipo del ejemplo del contrato");
        }

        @Test
        void reservingAndRidingMovesTheVehicle() {
            String id = purchase(newVehicle(), "tafira");

            publish(example("VehicleReserved", id));
            awaitVehicle(id, v -> v.get("status").asText().equals("RESERVED"));
            assertFalse(ids(json(get("/availability/vehicles?zoneId=tafira&limit=200", token("user")))).contains(id),
                    "un vehículo reservado ya no se ofrece a los usuarios");

            publish(example("RideStarted", id));
            awaitVehicle(id, v -> v.get("status").asText().equals("IN_USE"));
            ObjectNode finished = example("RideFinished", id);
            ((ObjectNode) finished.get("payload")).put("destinationZoneId", "mesa-y-lopez");
            publish(finished);

            JsonNode vehicle = awaitVehicle(id, v -> v.get("status").asText().equals("AVAILABLE"));
            assertEquals("mesa-y-lopez", vehicle.get("zoneId").asText());
        }

        @Test
        void maintenanceWithdrawsAndReturnsTheVehicle() {
            String id = purchase(newVehicle(), "vegueta");

            publish(example("VehicleBatteryLow", id));
            awaitVehicle(id, v -> v.get("status").asText().equals("LOW_BATTERY"));
            publish(example("VehicleWithdrawnForMaintenance", id));
            awaitVehicle(id, v -> v.get("status").asText().equals("WITHDRAWN") && !v.get("reservable").asBoolean());

            ObjectNode returned = example("VehicleReturnedToService", id);
            ((ObjectNode) returned.get("payload")).put("zoneId", "tafira");
            publish(returned);

            JsonNode vehicle = awaitVehicle(id, v -> v.get("status").asText().equals("AVAILABLE"));
            assertEquals("tafira", vehicle.get("zoneId").asText());
            assertFalse(vehicle.get("batteryLow").asBoolean());
        }

        @Test
        void aCompletedRelocationMovesTheVehicle() {
            String id = purchase(newVehicle(), "tafira");
            ObjectNode relocation = example("RelocationOrderCompleted", id);
            ((ObjectNode) relocation.get("payload")).put("targetZoneId", "triana");

            publish(relocation);

            awaitVehicle(id, v -> v.get("zoneId").asText().equals("triana"));
        }
    }

    @Nested
    class IdempotentConsumer {
        @Test
        void aRepeatedEventIsIgnored() {
            String id = purchase(newVehicle(), "tafira");
            ObjectNode reserved = example("VehicleReserved", id);
            publish(reserved);
            awaitVehicle(id, v -> v.get("status").asText().equals("RESERVED"));
            publish(example("ReservationCancelled", id));
            awaitVehicle(id, v -> v.get("status").asText().equals("AVAILABLE"));

            publish(reserved);
            pause(2500);

            assertEquals("AVAILABLE", json(get("/availability/vehicles/" + id, STAFF)).get("status").asText());
        }

        @Test
        void anIllegalEventIsRejectedWithoutGoingToTheDlq() {
            String id = purchase(newVehicle(), "tafira");
            ObjectNode finished = example("RideFinished", id);
            ((ObjectNode) finished.get("payload")).put("destinationZoneId", "triana");
            String eventId = finished.get("eventId").asText();

            publish(finished);
            pause(2500);

            JsonNode vehicle = json(get("/availability/vehicles/" + id, STAFF));
            assertEquals("AVAILABLE", vehicle.get("status").asText());
            assertEquals("tafira", vehicle.get("zoneId").asText());
            assertFalse(awaitInDlqQuickly(eventId), "un evento rechazado no va a la DLQ");
        }
    }

    @Nested
    class DeadLetterQueue {
        @Test
        void anEventForAnUnknownVehicleEndsInTheDlq() {
            ObjectNode reserved = example("VehicleReserved", newVehicle());

            publish(reserved);

            assertTrue(awaitInDlq(reserved.get("eventId").asText()));
        }

        @Test
        void aMalformedMessageEndsInTheDlq() {
            String marker = "malformed-" + UUID.randomUUID();

            publishRaw("{\"eventType\":\"VehicleReserved\",\"note\":\"" + marker + "\"}", "VehicleReserved");

            assertTrue(awaitInDlq(marker));
        }
    }

    @Nested
    class Queries {
        @Test
        void aUserOnlySeesAvailableVehiclesEvenIfAskingForOthers() {
            String id = purchase(newVehicle(), "santa-catalina");
            publish(example("VehicleReserved", id));
            awaitVehicle(id, v -> v.get("status").asText().equals("RESERVED"));

            JsonNode asUser = json(get("/availability/vehicles?status=RESERVED&zoneId=santa-catalina", token("user")));
            JsonNode asStaff = json(get("/availability/vehicles?status=RESERVED&zoneId=santa-catalina", STAFF));

            assertTrue(asUser.get("items").findValuesAsText("status").stream().allMatch("AVAILABLE"::equals));
            assertTrue(ids(asStaff).contains(id));
        }

        @Test
        void filtersByZoneAndTypeAndPaginates() {
            String zone = "zona-" + UUID.randomUUID().toString().substring(0, 6);
            List<String> created = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                created.add(purchase(newVehicle(), zone));
            }

            List<String> seen = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            do {
                JsonNode page = json(get("/availability/vehicles?zoneId=" + zone + "&vehicleType=SCOOTER&limit=1"
                        + (cursor == null ? "" : "&cursor=" + cursor), token("user")));
                seen.addAll(ids(page));
                cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asText();
                pages++;
            } while (cursor != null && pages < 10);

            assertEquals(created.stream().sorted().toList(), seen, "todos, uno por página, ordenados por id");
        }

        @Test
        void theZoneSummaryCountsVehiclesByStatus() {
            String zone = "zona-" + UUID.randomUUID().toString().substring(0, 6);
            purchase(newVehicle(), zone);
            String reserved = purchase(newVehicle(), zone);
            publish(example("VehicleReserved", reserved));
            awaitVehicle(reserved, v -> v.get("status").asText().equals("RESERVED"));

            JsonNode summary = json(get("/availability/zones/summary?zoneId=" + zone, STAFF));

            assertEquals(1, summary.get("items").size());
            JsonNode counts = summary.get("items").get(0).get("counts");
            assertEquals(1, counts.get("AVAILABLE").asInt());
            assertEquals(1, counts.get("RESERVED").asInt());
            assertEquals(0, counts.get("WITHDRAWN").asInt());
        }

        @Test
        void securityAndErrors() {
            assertEquals(401, get("/availability/vehicles", null).statusCode());
            assertEquals(403, get("/availability/zones/summary", token("user")).statusCode());
            assertEquals(404, get("/availability/vehicles/veh-does-not-exist", STAFF).statusCode());
            HttpResponse<String> bad = get("/availability/vehicles?status=FLYING", STAFF);
            assertEquals(400, bad.statusCode());
            assertTrue(json(bad).hasNonNull("correlationId"));
        }
    }

    @Test
    void longPollingOnAnEmptyQueueDoesNotTimeOut() {
        String emptyQueue = SQS.createQueue(r -> r.queueName("empty-" + UUID.randomUUID())).queueUrl();
        int waitSeconds = 5;
        var config = new com.urbanmove.availability.infrastructure.ServiceConfig("test", 0, "us-east-1",
                "http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566), "unused", false,
                emptyQueue, waitSeconds, null, TEST_JWT_SECRET);
        try (var http = com.urbanmove.availability.infrastructure.aws.AwsClients.longPollingHttpClient(waitSeconds);
             var sqs = com.urbanmove.availability.infrastructure.aws.AwsClients.sqs(config, http)) {
            long start = System.nanoTime();

            var messages = assertDoesNotThrow(() -> sqs.receiveMessage(r -> r.queueUrl(emptyQueue)
                    .waitTimeSeconds(waitSeconds)
                    .overrideConfiguration(c -> c.apiCallTimeout(java.time.Duration.ofSeconds(waitSeconds + 5L))
                            .apiCallAttemptTimeout(java.time.Duration.ofSeconds(waitSeconds + 5L)))).messages());

            assertTrue(messages.isEmpty());
            assertTrue(System.nanoTime() - start >= java.time.Duration.ofSeconds(waitSeconds - 1).toNanos(),
                    "SQS ha esperado de verdad antes de contestar");
        }
    }

    private static boolean awaitInDlqQuickly(String text) {
        return SQS.receiveMessage(r -> r.queueUrl(DLQ_URL).maxNumberOfMessages(10).waitTimeSeconds(1)
                .visibilityTimeout(0)).messages().stream().anyMatch(m -> m.body().contains(text));
    }
}
