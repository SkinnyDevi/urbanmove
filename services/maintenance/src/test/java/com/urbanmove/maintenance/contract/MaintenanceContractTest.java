package com.urbanmove.maintenance.contract;

import static com.urbanmove.maintenance.contract.Contracts.assertEventMatchesContract;
import static com.urbanmove.maintenance.contract.Contracts.assertResponseMatchesContract;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.urbanmove.maintenance.integration.MaintenanceApiTestSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Test de contrato (AGENTS.md 9.4) con el servicio real: las respuestas HTTP cumplen
 * docs/contracts/openapi/maintenance.yaml y los eventos que llegan a SQS cumplen
 * docs/contracts/asyncapi. Si el código y los contratos dejan de coincidir, este test falla.
 */
class MaintenanceContractTest extends MaintenanceApiTestSupport {

    private static final String TECH = token("maintenance");
    private static final String ORDERS = "/maintenance-orders";
    private static final String ORDER = "/maintenance-orders/{orderId}";

    @Test
    void healthMatchesTheContract() {
        assertResponseMatchesContract("GET", "/health", get("/health", null));
    }

    @Test
    void theWholeFlowMatchesTheRestAndEventContracts() {
        HttpResponse<String> opened = post(ORDERS, TECH, Map.of("vehicleId", newVehicle(), "vehicleType", "BIKE",
                "reason", "BREAKDOWN", "zoneId", "triana", "notes", "Freno trasero",
                "triggeredByEventId", "8c1e0c2a-1111-4222-8333-444455556666"));
        assertResponseMatchesContract("POST", ORDERS, opened);
        String id = json(opened).get("maintenanceOrderId").asText();
        String base = ORDERS + "/" + id;

        assertResponseMatchesContract("GET", ORDER, get(base, TECH));
        assertResponseMatchesContract("GET", ORDERS, get(ORDERS + "?status=PENDING,WITHDRAWN&limit=5", TECH));
        assertResponseMatchesContract("POST", ORDER + "/withdraw", post(base + "/withdraw", TECH, null));
        assertResponseMatchesContract("POST", ORDER + "/start-repair", post(base + "/start-repair", TECH, null));
        assertResponseMatchesContract("POST", ORDER + "/start-charging", post(base + "/start-charging", TECH, null));
        assertResponseMatchesContract("POST", ORDER + "/mark-ready", post(base + "/mark-ready", TECH, null));
        assertResponseMatchesContract("POST", ORDER + "/return-to-service",
                post(base + "/return-to-service", TECH, Map.of("zoneId", "tafira")));

        List<JsonNode> events = awaitEvents(id, 2);
        events.forEach(Contracts::assertEventMatchesContract);
        java.util.Set<String> work = new java.util.HashSet<>();
        events.get(1).get("payload").get("workPerformed").forEach(w -> work.add(w.asText()));
        assertEquals(java.util.Set.of("REPAIR", "RECHARGE"), work, "es un conjunto: no importa el orden");
    }

    @Test
    void cancellationMatchesTheContract() {
        String id = openOrder(newVehicle());
        assertResponseMatchesContract("POST", ORDER + "/cancel",
                post(ORDERS + "/" + id + "/cancel", TECH, Map.of("reason", "Duplicada")));
    }

    @Test
    void errorsMatchTheContract() {
        String vehicle = newVehicle();
        String id = openOrder(vehicle);

        assertResponseMatchesContract("POST", ORDERS, post(ORDERS, TECH, Map.of("vehicleId", "bad")));          // 400
        assertResponseMatchesContract("POST", ORDERS, post(ORDERS, null, Map.of()));                            // 401
        assertResponseMatchesContract("POST", ORDERS, post(ORDERS, token("user"), Map.of()));                   // 403
        assertResponseMatchesContract("POST", ORDERS, post(ORDERS, TECH, openBody(vehicle)));                   // 409
        assertResponseMatchesContract("GET", ORDER, get(ORDERS + "/mnt-missing", TECH));                       // 404
        assertResponseMatchesContract("POST", ORDER + "/mark-ready",
                post(ORDERS + "/" + id + "/mark-ready", TECH, null));                                         // 409 transición
    }

    @Test
    void theEventsThatReachTheQueueMatchTheirContract() {
        String id = openOrder(newVehicle());
        post(ORDERS + "/" + id + "/withdraw", TECH, null);

        JsonNode event = awaitEvents(id, 1).get(0);

        assertEventMatchesContract(event);
    }
}
