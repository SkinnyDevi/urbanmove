package com.urbanmove.relocation.contract;

import static com.urbanmove.relocation.contract.Contracts.assertResponseMatchesContract;

import com.fasterxml.jackson.databind.JsonNode;
import com.urbanmove.relocation.integration.RelocationTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Las respuestas REST y los eventos que llegan al bus cumplen relocation.yaml y AsyncAPI. */
class RelocationContractTest extends RelocationTestSupport {
    private static final String TECH = token("maintenance");
    private static final String ORDERS = "/relocation-orders";
    private static final String ORDER = "/relocation-orders/{orderId}";

    private static String openOrder() {
        String zone = newZone();
        publish(hotspot(zone, "las-canteras", 2));
        return awaitOrderTo(zone).get("relocationOrderId").asText();
    }

    @Test
    void healthMatchesTheContract() {
        assertResponseMatchesContract("GET", "/health", get("/health", null));
    }

    @Test
    void theWholeFlowMatchesTheRestAndEventContracts() {
        String id = openOrder();
        String base = ORDERS + "/" + id;

        assertResponseMatchesContract("GET", ORDER, get(base, TECH));
        assertResponseMatchesContract("GET", ORDERS, get(ORDERS + "?status=CREATED,ASSIGNED&limit=5", TECH));
        assertResponseMatchesContract("POST", ORDER + "/assign", post(base + "/assign", TECH, null));
        assertResponseMatchesContract("POST", ORDER + "/start", post(base + "/start", TECH, null));
        assertResponseMatchesContract("POST", ORDER + "/complete",
                post(base + "/complete", TECH, Map.of("vehicleIds", List.of("veh-0063"))));

        List<JsonNode> events = awaitEvents(id, 2);
        events.forEach(Contracts::assertEventMatchesContract);
    }

    @Test
    void cancellationMatchesTheContract() {
        assertResponseMatchesContract("POST", ORDER + "/cancel",
                post(ORDERS + "/" + openOrder() + "/cancel", token("admin"), Map.of("reason", "Déficit resuelto")));
    }

    @Test
    void errorsMatchTheContract() {
        String base = ORDERS + "/" + openOrder();

        assertResponseMatchesContract("GET", ORDERS, get(ORDERS, null));
        assertResponseMatchesContract("GET", ORDERS, get(ORDERS, token("user")));
        assertResponseMatchesContract("GET", ORDERS, get(ORDERS + "?status=FLYING", TECH));
        assertResponseMatchesContract("GET", ORDER, get(ORDERS + "/rel-missing", TECH));
        assertResponseMatchesContract("POST", ORDER + "/start", post(base + "/start", TECH, null));
        assertResponseMatchesContract("POST", ORDER + "/complete",
                post(base + "/complete", TECH, Map.of("vehicleIds", List.of())));
        post(base + "/assign", TECH, null);
        assertResponseMatchesContract("POST", ORDER + "/start",
                post(base + "/start", token("maintenance", "usr-tech-other"), null));
    }
}
