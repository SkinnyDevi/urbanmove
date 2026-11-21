package com.urbanmove.maintenance.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.urbanmove.maintenance.application.port.OutgoingEvent;
import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.Identifiers.TechnicianId;
import com.urbanmove.maintenance.domain.Identifiers.VehicleId;
import com.urbanmove.maintenance.domain.Identifiers.ZoneId;
import com.urbanmove.maintenance.domain.MaintenanceReason;
import com.urbanmove.maintenance.domain.MaintenanceWork;
import com.urbanmove.maintenance.domain.VehicleType;
import com.urbanmove.maintenance.domain.event.VehicleReturnedToService;
import com.urbanmove.maintenance.domain.event.VehicleWithdrawnForMaintenance;
import com.urbanmove.maintenance.infrastructure.events.EventEnvelopes;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Test de contrato sin Docker: el JSON que genera {@link EventEnvelopes} cumple los esquemas de
 * docs/contracts/asyncapi (envelope + payload + source fijado por evento).
 */
class EventEnvelopeContractTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EventEnvelopes ENVELOPES = new EventEnvelopes(JSON);
    private static final Instant AT = Instant.parse("2026-11-15T10:30:00Z");

    private static JsonNode serialize(com.urbanmove.maintenance.domain.event.DomainEvent event) throws Exception {
        return JSON.readTree(ENVELOPES.toJson(new OutgoingEvent(UUID.randomUUID(), UUID.randomUUID().toString(), event)));
    }

    private static VehicleWithdrawnForMaintenance withdrawn() {
        return new VehicleWithdrawnForMaintenance(new VehicleId("veh-0042"), VehicleType.SCOOTER,
                new MaintenanceOrderId("mnt-0031"), MaintenanceReason.LOW_BATTERY, new ZoneId("mesa-y-lopez"),
                new TechnicianId("usr-tech-02"), AT);
    }

    @Test
    void vehicleWithdrawnForMaintenanceMatchesItsContract() throws Exception {
        JsonNode event = serialize(withdrawn());

        Contracts.assertEventMatchesContract(event);
        assertEquals("2026-11-15T10:30:00.000Z", event.get("timestamp").asText(), "UTC con milisegundos");
        assertEquals("1.0.0", event.get("schemaVersion").asText());
    }

    @Test
    void vehicleReturnedToServiceMatchesItsContract() throws Exception {
        JsonNode event = serialize(new VehicleReturnedToService(new VehicleId("veh-0042"), VehicleType.SCOOTER,
                new MaintenanceOrderId("mnt-0031"), List.of(MaintenanceWork.RECHARGE, MaintenanceWork.REPAIR),
                new ZoneId("tafira"), 9000, AT));

        Contracts.assertEventMatchesContract(event);
    }

    /** Si el validador aceptara cualquier cosa, los tests anteriores no demostrarían nada. */
    @Test
    void theContractRejectsAWrongEvent() throws Exception {
        ObjectNode event = (ObjectNode) serialize(withdrawn());
        event.put("source", "urbanmove.rides");

        assertFalse(Contracts.eventErrors(event).isEmpty(), "source debe ser urbanmove.maintenance");

        ObjectNode payload = (ObjectNode) serialize(withdrawn()).get("payload");
        payload.put("extra", "x");
        ObjectNode withExtra = (ObjectNode) serialize(withdrawn());
        withExtra.set("payload", payload);
        assertFalse(Contracts.eventErrors(withExtra).isEmpty(), "el payload no admite campos extra");
    }
}
