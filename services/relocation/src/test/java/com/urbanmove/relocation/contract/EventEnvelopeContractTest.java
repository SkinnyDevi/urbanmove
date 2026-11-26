package com.urbanmove.relocation.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.urbanmove.relocation.application.port.OutgoingEvent;
import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.Identifiers.VehicleId;
import com.urbanmove.relocation.domain.Identifiers.ZoneId;
import com.urbanmove.relocation.domain.RelocationStrategy;
import com.urbanmove.relocation.domain.VehicleType;
import com.urbanmove.relocation.domain.event.DomainEvent;
import com.urbanmove.relocation.domain.event.RelocationOrderCompleted;
import com.urbanmove.relocation.domain.event.RelocationOrderCreated;
import com.urbanmove.relocation.infrastructure.events.EventEnvelopes;
import com.urbanmove.relocation.infrastructure.events.HotspotTranslator;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Los eventos que publica relocation y el hotspot que consume cumplen sus esquemas AsyncAPI. */
class EventEnvelopeContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EventEnvelopes ENVELOPES = new EventEnvelopes(JSON);
    private static final Instant AT = Instant.parse("2026-11-16T08:00:05Z");

    private static JsonNode serialize(DomainEvent event) throws Exception {
        return JSON.readTree(ENVELOPES.toJson(new OutgoingEvent(UUID.randomUUID(), UUID.randomUUID().toString(), event)));
    }

    private static RelocationOrderCreated created() {
        return new RelocationOrderCreated(new RelocationOrderId("rel-0009"), new ZoneId("las-canteras"),
                new ZoneId("tafira"), VehicleType.SCOOTER, 5, RelocationStrategy.THRESHOLD_RULE, UUID.randomUUID(), AT);
    }

    @Test
    void relocationOrderCreatedMatchesItsContract() throws Exception {
        JsonNode event = serialize(created());

        Contracts.assertEventMatchesContract(event);
        assertEquals("2026-11-16T08:00:05.000Z", event.get("timestamp").asText(), "UTC con milisegundos");
    }

    @Test
    void relocationOrderCompletedMatchesItsContract() throws Exception {
        Contracts.assertEventMatchesContract(serialize(new RelocationOrderCompleted(new RelocationOrderId("rel-0009"),
                new ZoneId("las-canteras"), new ZoneId("tafira"), VehicleType.SCOOTER,
                List.of(new VehicleId("veh-0063"), new VehicleId("veh-0071")), AT, AT.plusSeconds(4200), 600)));
    }

    @Test
    void theContractRejectsAWrongSource() throws Exception {
        ObjectNode event = (ObjectNode) serialize(created());
        event.put("source", "urbanmove.rides");

        assertFalse(Contracts.eventErrors(event).isEmpty(), "source debe ser urbanmove.relocation");
    }

    @Test
    void theContractExampleOfTheConsumedHotspotIsUnderstood() throws Exception {
        String body = Files.readString(Contracts.CONTRACTS.resolve("asyncapi/examples/DemandHotspotDetected.json"));
        Contracts.assertEventMatchesContract(JSON.readTree(body));

        assertTrue(new HotspotTranslator(JSON).translate(body).isPresent());
    }
}
