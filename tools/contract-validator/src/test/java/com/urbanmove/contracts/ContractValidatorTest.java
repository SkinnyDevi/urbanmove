package com.urbanmove.contracts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.yaml.snakeyaml.Yaml;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests de contrato: comprueban que los 13 eventos están completos, que los ejemplos son válidos y
 * que el validador rechaza eventos mal formados.
 */
class ContractValidatorTest {

    static final Set<String> EXPECTED_EVENTS = Set.of(
            "UserRegistered", "VehiclePurchased", "VehicleReserved", "ReservationCancelled", "ReservationExpired",
            "RideStarted", "RideFinished", "VehicleBatteryLow", "VehicleWithdrawnForMaintenance",
            "VehicleReturnedToService", "DemandHotspotDetected", "RelocationOrderCreated", "RelocationOrderCompleted");

    static final ObjectMapper MAPPER = new ObjectMapper();
    static ContractValidator validator;
    static Path contracts;

    @BeforeAll
    static void loadContracts() {
        validator = ContractValidator.fromDefaultLocation();
        contracts = validator.contractsDir();
    }

    static Stream<String> eventTypes() {
        return EXPECTED_EVENTS.stream().sorted();
    }

    static ObjectNode example(String eventType) throws IOException {
        return (ObjectNode) MAPPER.readTree(contracts.resolve("examples").resolve(eventType + ".json").toFile());
    }

    static ObjectNode mutated(String eventType, Consumer<ObjectNode> change) throws IOException {
        ObjectNode event = example(eventType);
        change.accept(event);
        return event;
    }

    static ObjectNode payload(ObjectNode event) {
        return (ObjectNode) event.get("payload");
    }

    // ------------------------------------------------------------------ catálogo cerrado

    @Test
    void catalogHasExactlyThe13OfficialEvents() {
        assertEquals(EXPECTED_EVENTS, Set.copyOf(validator.knownEventTypes()));
    }

    @ParameterizedTest
    @MethodSource("eventTypes")
    void everyEventHasPayloadMessageAndExample(String eventType) {
        Path schemas = contracts.resolve("schemas");
        assertTrue(Files.isRegularFile(schemas.resolve("payloads").resolve(eventType + ".payload.schema.json")));
        assertTrue(Files.isRegularFile(schemas.resolve("messages").resolve(eventType + ".message.schema.json")));
        assertTrue(Files.isRegularFile(contracts.resolve("examples").resolve(eventType + ".json")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void asyncApiDocumentListsTheSameEvents() throws IOException {
        Map<String, Object> doc = new Yaml().load(Files.readString(contracts.resolve("asyncapi.yaml")));
        assertTrue(doc.get("asyncapi").toString().startsWith("3."));
        Map<String, Object> components = (Map<String, Object>) doc.get("components");
        Map<String, Map<String, Object>> messages = (Map<String, Map<String, Object>>) components.get("messages");
        Map<String, Object> channels = (Map<String, Object>) doc.get("channels");
        Map<String, Object> domainEvents = (Map<String, Object>) channels.get("domainEvents");
        assertEquals(EXPECTED_EVENTS, messages.keySet());
        assertEquals(EXPECTED_EVENTS, ((Map<String, Object>) domainEvents.get("messages")).keySet());
        messages.forEach((name, message) -> {
            assertTrue(message.get("x-producer").toString().startsWith("urbanmove."));
            assertTrue(((List<String>) message.get("x-consumers")).contains("analytics-ingestion"),
                    name + " no llega al Data Lake");
        });
    }

    // ------------------------------------------------------------------ ejemplos válidos

    @ParameterizedTest
    @MethodSource("eventTypes")
    void examplesAreValid(String eventType) throws IOException {
        assertEquals(List.of(), validator.validate(example(eventType)));
    }

    // ------------------------------------------------------------------ casos inválidos

    @Test
    void missingCorrelationIdIsRejected() throws IOException {
        var event = mutated("VehicleReserved", e -> e.remove("correlationId"));
        assertTrue(validator.validate(event).stream().anyMatch(error -> error.contains("correlationId")));
    }

    @Test
    void unknownEventTypeIsRejected() throws IOException {
        assertFalse(validator.validate(mutated("VehicleReserved", e -> e.put("eventType", "VehicleTeleported"))).isEmpty());
    }

    @Test
    void sourceMustMatchTheOfficialProducer() throws IOException {
        assertFalse(validator.validate(mutated("VehicleReserved", e -> e.put("source", "urbanmove.rides"))).isEmpty());
    }

    @Test
    void extraPayloadFieldsAreRejected() throws IOException {
        assertFalse(validator.validate(mutated("RideStarted", e -> payload(e).put("isBroken", true))).isEmpty());
    }

    @Test
    void batteryLevelOutOfRangeIsRejected() throws IOException {
        assertFalse(validator.validate(mutated("VehicleBatteryLow", e -> payload(e).put("batteryLevel", 150))).isEmpty());
    }

    @Test
    void invalidUuidIsRejected() throws IOException {
        assertFalse(validator.validate(mutated("RideFinished", e -> e.put("eventId", "not-a-uuid"))).isEmpty());
    }

    @Test
    void invalidTimestampIsRejected() throws IOException {
        assertFalse(validator.validate(mutated("RideFinished", e -> e.put("timestamp", "ayer por la tarde"))).isEmpty());
    }

    @Test
    void unknownVehicleTypeIsRejected() throws IOException {
        assertFalse(validator.validate(mutated("VehicleReserved", e -> payload(e).put("vehicleType", "SKATEBOARD"))).isEmpty());
    }

    @Test
    void rideWithoutReservationIsAllowed() throws IOException {
        assertEquals(List.of(), validator.validate(mutated("RideStarted", e -> payload(e).putNull("reservationId"))));
    }

    // ------------------------------------------------------------------ línea de comandos

    @Test
    void cliValidatesJsonlFiles(@TempDir Path tmp) throws IOException {
        ObjectNode good = example("VehicleReserved");
        ObjectNode bad = mutated("VehicleReserved", e -> e.remove("payload"));
        Path file = tmp.resolve("events.jsonl");
        Files.writeString(file, MAPPER.writeValueAsString(good) + "\n" + MAPPER.writeValueAsString(bad) + "\n");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int exitCode = ValidatorCli.run(new String[] {file.toString()},
                new PrintStream(buffer, true, StandardCharsets.UTF_8), validator);

        assertEquals(1, exitCode);
        assertTrue(buffer.toString(StandardCharsets.UTF_8).contains("1/2 eventos válidos"));
    }
}
