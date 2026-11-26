package com.urbanmove.inventory.contract;

import static com.urbanmove.inventory.contract.Contracts.assertEventMatchesContract;
import static com.urbanmove.inventory.contract.Contracts.assertResponseMatchesContract;
import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.inventory.application.port.OutgoingEvent;
import com.urbanmove.inventory.domain.VehicleId;
import com.urbanmove.inventory.domain.VehicleType;
import com.urbanmove.inventory.domain.event.VehicleRetired;
import com.urbanmove.inventory.infrastructure.events.EventEnvelopes;
import com.urbanmove.inventory.infrastructure.events.EventTranslator;
import com.urbanmove.inventory.integration.InventoryTestSupport;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Respuestas REST y eventos de inventory contra inventory.yaml y los esquemas AsyncAPI. */
class InventoryContractTest extends InventoryTestSupport {
    private static final String ADMIN = token("admin");
    private static final String VEHICLES = "/vehicles";
    private static final String VEHICLE = "/vehicles/{vehicleId}";
    private static final String RETIRE = "/vehicles/{vehicleId}/retire";

    @Test
    void healthMatchesTheContract() {
        assertResponseMatchesContract("GET", "/health", get("/health", null));
    }

    @Test
    void queriesMatchTheContract() {
        assertResponseMatchesContract("GET", VEHICLES, get(VEHICLES + "?vehicleType=BIKE&limit=5", ADMIN));
        assertResponseMatchesContract("GET", VEHICLE, get("/vehicles/veh-0004", token("maintenance")));
    }

    @Test
    void retirementMatchesTheRestAndEventContracts() {
        String id = withdrawnVehicle();

        assertResponseMatchesContract("POST", RETIRE, post("/vehicles/" + id + "/retire", ADMIN,
                Map.of("reason", "Fin de vida útil")));

        assertEventMatchesContract(awaitEvents(id, 1).get(0));
    }

    @Test
    void errorsMatchTheContract() {
        assertResponseMatchesContract("GET", VEHICLES, get(VEHICLES, null));
        assertResponseMatchesContract("GET", VEHICLES, get(VEHICLES, token("user")));
        assertResponseMatchesContract("GET", VEHICLES, get(VEHICLES + "?limit=0", ADMIN));
        assertResponseMatchesContract("GET", VEHICLE, get("/vehicles/veh-missing", ADMIN));
        assertResponseMatchesContract("POST", RETIRE, post("/vehicles/veh-0004/retire", ADMIN, Map.of("reason", "x")));
        assertResponseMatchesContract("POST", RETIRE, post("/vehicles/veh-0004/retire", ADMIN, Map.of()));
    }

    @Test
    void theEnvelopeOfVehicleRetiredMatchesItsSchema() throws Exception {
        ObjectMapper json = new ObjectMapper();
        JsonNode event = json.readTree(new EventEnvelopes(json).toJson(new OutgoingEvent(UUID.randomUUID(),
                UUID.randomUUID().toString(), new VehicleRetired(new VehicleId("veh-0042"), VehicleType.SCOOTER,
                        "Robado", Instant.parse("2026-11-20T12:00:00Z")))));

        assertEventMatchesContract(event);
        assertEquals("2026-11-20T12:00:00.000Z", event.get("timestamp").asText());
    }

    @Test
    void theContractExamplesOfConsumedEventsAreUnderstood() throws Exception {
        EventTranslator translator = new EventTranslator(new ObjectMapper());
        for (String type : new String[] {"VehiclePurchased", "VehicleWithdrawnForMaintenance", "VehicleReturnedToService"}) {
            String body = Files.readString(Contracts.CONTRACTS.resolve("asyncapi/examples/" + type + ".json"));
            assertTrue(translator.translate(body).isPresent(), type);
        }
        assertTrue(translator.translate(Files.readString(
                Contracts.CONTRACTS.resolve("asyncapi/examples/RideStarted.json"))).isEmpty(), "los demás se ignoran");
    }
}
