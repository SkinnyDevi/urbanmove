package com.urbanmove.relocation.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** El servicio completo contra DynamoDB, SNS y SQS en LocalStack. */
class RelocationIntegrationTest extends RelocationTestSupport {
    private static final String TECH = token("maintenance");

    private static String orderPath(JsonNode order) {
        return "/relocation-orders/" + order.get("relocationOrderId").asText();
    }

    @Test
    void healthReportsItsDependencies() {
        JsonNode checks = json(get("/health", null)).get("checks");

        assertEquals("UP", checks.get("dynamodb").asText());
        assertEquals("UP", checks.get("sns").asText());
        assertEquals("UP", checks.get("sqs").asText());
    }

    @Test
    void aHotspotCreatesAnOrderAndPublishesRelocationOrderCreated() {
        String zone = newZone();
        ObjectNode hotspot = hotspot(zone, "las-canteras", 4);

        publish(hotspot);

        JsonNode order = awaitOrderTo(zone);
        assertEquals("CREATED", order.get("status").asText());
        assertEquals("las-canteras", order.get("sourceZoneId").asText());
        assertEquals(4, order.get("quantity").asInt());
        assertEquals(hotspot.get("eventId").asText(), order.get("triggeredByEventId").asText());
        JsonNode created = awaitEvents(order.get("relocationOrderId").asText(), 1).get(0);
        assertEquals("RelocationOrderCreated", created.get("eventType").asText());
        assertEquals(hotspot.get("correlationId").asText(), created.get("correlationId").asText());
    }

    @Test
    void theSameHotspotTwiceAndAnotherForTheSameZoneCreateASingleOrder() {
        String zone = newZone();
        ObjectNode hotspot = hotspot(zone, "las-canteras", 4);
        publish(hotspot);
        awaitOrderTo(zone);

        publish(hotspot);
        publish(hotspot(zone, "triana", 2));
        pause(3000);

        assertEquals(1, ordersTo(zone).size());
    }

    @Test
    void theTechnicianTakesStartsAndCompletesTheOrder() {
        String zone = newZone();
        publish(hotspot(zone, "las-canteras", 3));
        JsonNode order = awaitOrderTo(zone);
        String path = orderPath(order);

        assertEquals("ASSIGNED", json(post(path + "/assign", TECH, null)).get("status").asText());
        assertEquals("IN_PROGRESS", json(post(path + "/start", TECH, null)).get("status").asText());
        JsonNode done = json(post(path + "/complete", TECH, Map.of("vehicleIds", List.of("veh-0063", "veh-0071"))));

        assertEquals("COMPLETED", done.get("status").asText());
        assertEquals("usr-tech-it", done.get("assigneeId").asText());
        assertEquals(0, done.get("delaySeconds").asInt());
        JsonNode completed = awaitEvents(order.get("relocationOrderId").asText(), 2).get(1);
        assertEquals("RelocationOrderCompleted", completed.get("eventType").asText());
        assertEquals(2, completed.at("/payload/vehicleIds").size());
        assertEquals(zone, completed.at("/payload/targetZoneId").asText());
    }

    @Test
    void anotherTechnicianCannotStartTheOrder() {
        String zone = newZone();
        publish(hotspot(zone, "las-canteras", 3));
        String path = orderPath(awaitOrderTo(zone));
        post(path + "/assign", TECH, null);

        HttpResponse<String> response = post(path + "/start", token("maintenance", "usr-tech-other"), null);

        assertEquals(403, response.statusCode());
        assertEquals("/problems/not-assignee", json(response).get("type").asText());
    }

    @Test
    void invalidActionsAreRejected() {
        String zone = newZone();
        publish(hotspot(zone, "las-canteras", 1));
        String path = orderPath(awaitOrderTo(zone));

        assertEquals(409, post(path + "/start", TECH, null).statusCode());
        post(path + "/assign", TECH, null);
        post(path + "/start", TECH, null);
        HttpResponse<String> tooMany = post(path + "/complete", TECH, Map.of("vehicleIds", List.of("veh-1", "veh-2")));
        HttpResponse<String> empty = post(path + "/complete", TECH, Map.of("vehicleIds", List.of()));

        assertEquals(400, tooMany.statusCode());
        assertEquals(400, empty.statusCode());
    }

    @Test
    void onlyAnAdminCanCancel() {
        String zone = newZone();
        publish(hotspot(zone, "las-canteras", 2));
        String path = orderPath(awaitOrderTo(zone));

        assertEquals(403, post(path + "/cancel", TECH, Map.of("reason", "x")).statusCode());
        JsonNode cancelled = json(post(path + "/cancel", token("admin"), Map.of("reason", "Déficit resuelto")));

        assertEquals("CANCELLED", cancelled.get("status").asText());
        assertEquals("Déficit resuelto", cancelled.get("cancellationReason").asText());
    }

    @Test
    void aMalformedHotspotEndsInTheDlq() {
        String marker = "malformed-" + UUID.randomUUID();

        publish("{\"eventType\":\"DemandHotspotDetected\",\"note\":\"" + marker + "\"}", "DemandHotspotDetected");

        assertTrue(awaitInDlq(marker));
    }

    @Test
    void securityAndUnknownOrders() {
        assertEquals(401, get("/relocation-orders", null).statusCode());
        assertEquals(403, get("/relocation-orders", token("user")).statusCode());
        assertEquals(404, get("/relocation-orders/rel-missing", TECH).statusCode());
        assertEquals(400, get("/relocation-orders?status=FLYING", TECH).statusCode());
    }
}
