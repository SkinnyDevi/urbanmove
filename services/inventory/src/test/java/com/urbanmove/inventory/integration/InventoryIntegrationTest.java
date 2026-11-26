package com.urbanmove.inventory.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El servicio completo contra DynamoDB, SNS y SQS en LocalStack. */
class InventoryIntegrationTest extends InventoryTestSupport {
    private static final String ADMIN = token("admin");

    private static HttpResponse<String> retire(String vehicleId, String reason) {
        return post("/vehicles/" + vehicleId + "/retire", ADMIN, Map.of("reason", reason));
    }

    @Test
    void healthReportsItsDependencies() {
        JsonNode checks = json(get("/health", null)).get("checks");

        assertEquals("UP", checks.get("dynamodb").asText());
        assertEquals("UP", checks.get("sns").asText());
        assertEquals("UP", checks.get("sqs").asText());
    }

    @Test
    void theLocalFleetIsSeededAtStartup() {
        JsonNode vehicle = json(get("/vehicles/veh-0004", token("maintenance")));

        assertEquals("BIKE", vehicle.get("vehicleType").asText());
        assertEquals("ACTIVE", vehicle.get("adminStatus").asText());
        assertEquals("EUR", vehicle.at("/purchasePrice/currency").asText());
    }

    @Test
    void vehiclePurchasedRegistersTheAsset() {
        String id = newVehicle();

        publish(example("VehiclePurchased", id));

        JsonNode vehicle = awaitVehicle(id, "IN_SERVICE");
        assertEquals("Segway Ninebot Max G30", vehicle.get("model").asText());
        assertEquals("2026-11-02", vehicle.get("purchaseDate").asText());
        assertEquals(649.0, vehicle.at("/purchasePrice/amount").asDouble());
    }

    @Test
    void aWithdrawnVehicleIsRetiredAndVehicleRetiredIsPublished() {
        String id = withdrawnVehicle();

        HttpResponse<String> response = retire(id, "Chasis dañado sin reparación posible");

        assertEquals(200, response.statusCode());
        JsonNode vehicle = json(response);
        assertEquals("RETIRED", vehicle.get("adminStatus").asText());
        assertEquals("RETIRED", vehicle.get("serviceStatus").asText());
        JsonNode event = awaitEvents(id, 1).get(0);
        assertEquals("VehicleRetired", event.get("eventType").asText());
        assertEquals("urbanmove.inventory", event.get("source").asText());
        assertEquals("Chasis dañado sin reparación posible", event.at("/payload/reason").asText());
        assertEquals(response.headers().firstValue("X-Correlation-Id").orElseThrow(),
                event.get("correlationId").asText());
    }

    @Test
    void aVehicleOnTheStreetCannotBeRetired() {
        String id = newVehicle();
        publish(example("VehiclePurchased", id));
        awaitVehicle(id, "IN_SERVICE");

        HttpResponse<String> response = retire(id, "Fin de vida útil");

        assertEquals(409, response.statusCode());
        assertEquals("IN_SERVICE", json(response).get("currentState").asText());
    }

    @Test
    void aVehicleReturnedToServiceIsOnTheStreetAgain() {
        String id = withdrawnVehicle();

        ObjectNode back = example("VehicleReturnedToService", id);
        publish(back);

        assertEquals("ACTIVE", awaitVehicle(id, "IN_SERVICE").get("adminStatus").asText());
    }

    @Test
    void retiredVehiclesCanBeFiltered() {
        String id = withdrawnVehicle();
        retire(id, "Robado");

        JsonNode page = json(get("/vehicles?adminStatus=RETIRED&vehicleType=SCOOTER&limit=200", ADMIN));

        boolean found = false;
        for (JsonNode item : page.get("items")) {
            assertEquals("RETIRED", item.get("adminStatus").asText());
            found |= id.equals(item.get("vehicleId").asText());
        }
        assertTrue(found);
    }

    @Test
    void listingIsPaginated() {
        JsonNode first = json(get("/vehicles?limit=10", ADMIN));
        String cursor = first.get("nextCursor").asText();
        JsonNode second = json(get("/vehicles?limit=10&cursor=" + cursor, ADMIN));

        assertEquals(10, first.get("items").size());
        assertNotEquals(first.at("/items/0/vehicleId").asText(), second.at("/items/0/vehicleId").asText());
    }

    @Test
    void aMalformedEventEndsInTheDlq() {
        String marker = "malformed-" + UUID.randomUUID();

        publish("{\"eventType\":\"VehiclePurchased\",\"note\":\"" + marker + "\"}", "VehiclePurchased");

        assertTrue(awaitInDlq(marker));
    }

    @Test
    void securityAndErrors() {
        assertEquals(401, get("/vehicles", null).statusCode());
        assertEquals(403, get("/vehicles", token("user")).statusCode());
        assertEquals(403, post("/vehicles/veh-0004/retire", token("maintenance"), Map.of("reason", "x")).statusCode());
        assertEquals(404, get("/vehicles/veh-missing", ADMIN).statusCode());
        assertEquals(400, get("/vehicles?adminStatus=BROKEN", ADMIN).statusCode());
        assertEquals(400, post("/vehicles/veh-0004/retire", ADMIN, Map.of()).statusCode());
    }
}
