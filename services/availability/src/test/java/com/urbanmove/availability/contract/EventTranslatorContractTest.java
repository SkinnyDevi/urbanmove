package com.urbanmove.availability.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.urbanmove.availability.application.IncomingEvent;
import com.urbanmove.availability.application.VehicleEvent;
import com.urbanmove.availability.infrastructure.events.EventTranslator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** Comprueba que el traductor entiende los ejemplos oficiales de cada evento consumido. */
class EventTranslatorContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final EventTranslator TRANSLATOR = new EventTranslator(JSON);
    private static final Path EXAMPLES = findExamples();

    private static Path findExamples() {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("docs/contracts/asyncapi/examples");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("docs/contracts/asyncapi/examples not found");
    }

    static Stream<String> consumedEvents() {
        return Stream.of("VehiclePurchased", "VehicleReserved", "ReservationCancelled", "ReservationExpired",
                "RideStarted", "RideFinished", "VehicleBatteryLow", "VehicleWithdrawnForMaintenance",
                "VehicleReturnedToService", "RelocationOrderCompleted");
    }

    private static String read(String eventType) throws Exception {
        return Files.readString(EXAMPLES.resolve(eventType + ".json"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("consumedEvents")
    void theOfficialExampleIsUnderstood(String eventType) throws Exception {
        Optional<IncomingEvent> event = TRANSLATOR.translate(read(eventType));

        assertTrue(event.isPresent(), eventType + " debería consumirse");
        assertEquals(eventType, event.get().metadata().eventType());
        assertNotNull(event.get().metadata().correlationId());
    }

    @Test
    void theRelevantFieldsAreRead() throws Exception {
        VehicleEvent.RideFinished finished = (VehicleEvent.RideFinished) TRANSLATOR.translate(read("RideFinished"))
                .orElseThrow().event();
        VehicleEvent.Relocated relocated = (VehicleEvent.Relocated) TRANSLATOR.translate(read("RelocationOrderCompleted"))
                .orElseThrow().event();

        assertEquals(JSON.readTree(read("RideFinished")).at("/payload/destinationZoneId").asText(),
                finished.destination().value());
        assertEquals(JSON.readTree(read("RelocationOrderCompleted")).at("/payload/vehicleIds").size(),
                relocated.vehicleIds().size());
    }

    @Test
    void eventsThatAvailabilityDoesNotConsumeAreIgnored() throws Exception {
        assertTrue(TRANSLATOR.translate(read("UserRegistered")).isEmpty());
        assertTrue(TRANSLATOR.translate(read("DemandHotspotDetected")).isEmpty());
    }

    @Test
    void aMalformedEventIsReported() throws Exception {
        ObjectNode event = (ObjectNode) JSON.readTree(read("VehicleReserved"));
        ((ObjectNode) event.get("payload")).remove("vehicleId");

        assertThrows(EventTranslator.MalformedEventException.class, () -> TRANSLATOR.translate(event.toString()));
        assertThrows(EventTranslator.MalformedEventException.class, () -> TRANSLATOR.translate("{not json"));
    }
}
