package com.urbanmove.synthgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.urbanmove.contracts.ContractValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprueban que los datos generados son válidos, coherentes y reproducibles:
 * si alguien cambia el generador y rompe algo, estos tests lo detectan.
 */
class GeneratorTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    /** Escenario pequeño para que los tests sean rápidos. */
    static final Settings SMALL = Settings.defaults().withStartDate(LocalDate.of(2026, 11, 9)).withDays(2)
            .withSeed(7).withVehicles(60).withUsers(120).withTripsPerDay(500);

    static GenerationResult result;
    static List<ObjectNode> events;

    @BeforeAll
    static void generateOnce() {
        result = Simulator.generate(SMALL);
        events = result.events();
    }

    static List<ObjectNode> ofType(String eventType) {
        return events.stream().filter(e -> eventType.equals(e.get("eventType").asText())).toList();
    }

    static String text(JsonNode node, String field) {
        return node.get(field).asText();
    }

    static List<String> asJson(List<ObjectNode> list) {
        return list.stream().map(OutputWriter::toJson).toList();
    }

    // ------------------------------------------------------------------ contratos

    @Test
    void everyEventIsValidAgainstTheContracts() {
        ContractValidator validator = ContractValidator.fromDefaultLocation();
        for (ObjectNode event : events) {
            assertEquals(List.of(), validator.validate(event), event.toString());
        }
    }

    @Test
    void relocationEventsAreLeftToTheRelocationEngine() {
        Set<String> produced = events.stream().map(e -> text(e, "eventType")).collect(Collectors.toSet());
        assertFalse(produced.contains("DemandHotspotDetected"));
        assertFalse(produced.contains("RelocationOrderCreated"));
        assertFalse(produced.contains("RelocationOrderCompleted"));
        assertTrue(produced.containsAll(Set.of("VehicleReserved", "RideStarted", "RideFinished", "VehicleBatteryLow",
                "VehicleWithdrawnForMaintenance", "VehicleReturnedToService")));
    }

    @Test
    void eventIdsAreUnique() {
        Set<String> ids = events.stream().map(e -> text(e, "eventId")).collect(Collectors.toSet());
        assertEquals(events.size(), ids.size());
    }

    // ------------------------------------------------------------------ reproducibilidad

    @Test
    void sameSeedProducesIdenticalData() {
        assertEquals(asJson(events), asJson(Simulator.generate(SMALL.withValidate(false)).events()));
    }

    @Test
    void differentSeedProducesDifferentData() {
        assertNotEquals(asJson(events), asJson(Simulator.generate(SMALL.withSeed(8).withValidate(false)).events()));
    }

    // ------------------------------------------------------------------ coherencia temporal

    @Test
    void eventsAreInChronologicalOrder() {
        for (int i = 1; i < events.size(); i++) {
            assertTrue(text(events.get(i - 1), "timestamp").compareTo(text(events.get(i), "timestamp")) <= 0);
        }
    }

    static final Map<String, String> VALID_TRANSITIONS = Map.ofEntries(
            Map.entry("AVAILABLE|VehicleReserved", "RESERVED"),
            Map.entry("RESERVED|RideStarted", "IN_USE"),
            Map.entry("AVAILABLE|RideStarted", "IN_USE"),                     // viaje sin reserva previa
            Map.entry("RESERVED|ReservationCancelled", "AVAILABLE"),
            Map.entry("RESERVED|ReservationExpired", "AVAILABLE"),
            Map.entry("IN_USE|RideFinished", "AVAILABLE"),
            Map.entry("AVAILABLE|VehicleBatteryLow", "LOW_BATTERY"),
            Map.entry("LOW_BATTERY|VehicleWithdrawnForMaintenance", "WITHDRAWN"),
            Map.entry("AVAILABLE|VehicleWithdrawnForMaintenance", "WITHDRAWN"),   // avería o revisión
            Map.entry("WITHDRAWN|VehicleReturnedToService", "AVAILABLE"));

    /**
     * Recorre los eventos de cada vehículo y comprueba que cada paso es una transición permitida.
     * Si un vehículo se reservara dos veces a la vez, fallaría aquí (RESERVED + VehicleReserved).
     */
    @Test
    void everyVehicleFollowsTheStateMachine() {
        Map<String, String> state = new HashMap<>();
        for (ObjectNode e : events) {
            JsonNode payload = e.get("payload");
            if ("VehiclePurchased".equals(text(e, "eventType"))) {
                state.put(text(payload, "vehicleId"), "AVAILABLE");
                continue;
            }
            if (!payload.has("vehicleId")) {
                continue;
            }
            String vehicleId = text(payload, "vehicleId");
            String key = state.get(vehicleId) + "|" + text(e, "eventType");
            assertTrue(VALID_TRANSITIONS.containsKey(key),
                    vehicleId + ": transición imposible " + key + " en " + text(e, "timestamp"));
            state.put(vehicleId, VALID_TRANSITIONS.get(key));
        }
    }

    /**
     * Lo que se deduce de los eventos debe coincidir con el estado real de cada vehículo al acabar.
     * Detecta vehículos "atascados" (por ejemplo, una reserva cancelada que no libera el vehículo):
     * esos fallos no aparecen en la secuencia de eventos porque el vehículo simplemente deja de usarse.
     */
    @Test
    void eventsMatchTheFinalStateOfEveryVehicle() {
        Map<String, String> state = new HashMap<>();
        Map<String, String> zone = new HashMap<>();
        for (ObjectNode e : events) {
            JsonNode p = e.get("payload");
            String type = text(e, "eventType");
            if (type.equals("VehiclePurchased")) {
                state.put(text(p, "vehicleId"), "AVAILABLE");
                zone.put(text(p, "vehicleId"), text(p, "initialZoneId"));
            } else if (p.has("vehicleId")) {
                state.put(text(p, "vehicleId"), VALID_TRANSITIONS.get(state.get(text(p, "vehicleId")) + "|" + type));
                if (type.equals("RideFinished")) {
                    zone.put(text(p, "vehicleId"), text(p, "destinationZoneId"));
                }
            }
        }
        for (Vehicle vehicle : result.vehicles()) {
            assertEquals(vehicle.state, state.get(vehicle.vehicleId), vehicle.vehicleId + ": estado distinto");
            assertEquals(vehicle.zoneId(), zone.get(vehicle.vehicleId), vehicle.vehicleId + ": zona distinta");
        }
    }

    @Test
    void vehicleIsWhereItsLastRideEnded() {
        Map<String, String> zone = new HashMap<>();
        for (ObjectNode e : events) {
            JsonNode p = e.get("payload");
            switch (text(e, "eventType")) {
                case "VehiclePurchased" -> zone.put(text(p, "vehicleId"), text(p, "initialZoneId"));
                case "RideStarted" -> assertEquals(zone.get(text(p, "vehicleId")), text(p, "originZoneId"));
                case "RideFinished" -> zone.put(text(p, "vehicleId"), text(p, "destinationZoneId"));
                default -> {
                    if (p.has("zoneId") && p.has("vehicleId")) {
                        assertEquals(zone.get(text(p, "vehicleId")), text(p, "zoneId"),
                                text(e, "eventType") + " en zona equivocada");
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ flujos y correlationId

    @Test
    void rideFlowSharesCorrelationId() {
        Map<String, ObjectNode> started = new HashMap<>();
        ofType("RideStarted").forEach(e -> started.put(text(e.get("payload"), "rideId"), e));
        Map<String, ObjectNode> reserved = new HashMap<>();
        ofType("VehicleReserved").forEach(e -> reserved.put(text(e.get("payload"), "reservationId"), e));
        for (ObjectNode finished : ofType("RideFinished")) {
            ObjectNode start = started.get(text(finished.get("payload"), "rideId"));
            assertNotNull(start);
            assertEquals(text(start, "correlationId"), text(finished, "correlationId"));
            assertTrue(text(start, "timestamp").compareTo(text(finished, "timestamp")) < 0);
            JsonNode reservationId = start.get("payload").get("reservationId");
            if (!reservationId.isNull()) {
                assertEquals(text(reserved.get(reservationId.asText()), "correlationId"), text(start, "correlationId"));
            }
        }
    }

    @Test
    void everyReservationHasExactlyOneOutcome() {
        Map<String, List<String>> outcomes = new HashMap<>();
        for (ObjectNode e : events) {
            String type = text(e, "eventType");
            JsonNode p = e.get("payload");
            if (type.equals("ReservationCancelled") || type.equals("ReservationExpired")) {
                outcomes.computeIfAbsent(text(p, "reservationId"), k -> new ArrayList<>()).add(type);
            } else if (type.equals("RideStarted") && !p.get("reservationId").isNull()) {
                outcomes.computeIfAbsent(text(p, "reservationId"), k -> new ArrayList<>()).add(type);
            }
        }
        for (ObjectNode reservation : ofType("VehicleReserved")) {
            assertEquals(1, outcomes.getOrDefault(text(reservation.get("payload"), "reservationId"), List.of()).size());
        }
    }

    @Test
    void expiredReservationsExpireExactlyWhenPromised() {
        Map<String, String> expires = new HashMap<>();
        ofType("VehicleReserved").forEach(e -> expires.put(text(e.get("payload"), "reservationId"),
                text(e.get("payload"), "expiresAt")));
        for (ObjectNode e : ofType("ReservationExpired")) {
            assertEquals(expires.get(text(e.get("payload"), "reservationId")), text(e.get("payload"), "expiredAt"));
        }
    }

    @Test
    void maintenanceFlowIsCompleteAndTraceable() {
        Map<String, ObjectNode> withdrawn = new HashMap<>();
        ofType("VehicleWithdrawnForMaintenance").forEach(e -> withdrawn.put(text(e.get("payload"), "maintenanceOrderId"), e));
        Map<String, ObjectNode> returned = new HashMap<>();
        ofType("VehicleReturnedToService").forEach(e -> returned.put(text(e.get("payload"), "maintenanceOrderId"), e));
        assertEquals(withdrawn.keySet(), returned.keySet());
        Set<String> lowBatteryCorr = new HashSet<>();
        ofType("VehicleBatteryLow").forEach(e -> lowBatteryCorr.add(text(e, "correlationId")));
        withdrawn.forEach((orderId, w) -> {
            assertEquals(text(w, "correlationId"), text(returned.get(orderId), "correlationId"));
            if ("LOW_BATTERY".equals(text(w.get("payload"), "reason"))) {
                assertTrue(lowBatteryCorr.contains(text(w, "correlationId")));
            }
        });
    }

    @Test
    void rideCostAndDurationAreConsistent() {
        for (ObjectNode e : ofType("RideFinished")) {
            JsonNode p = e.get("payload");
            assertTrue(p.get("durationSeconds").asLong() >= 120);
            assertTrue(p.get("preliminaryCost").get("amount").asDouble() > 0);
        }
    }

    // ------------------------------------------------------------------ demanda

    @Test
    void defaultScenarioProducesDeficitsForTheRelocationEngine() {
        GenerationResult week = Simulator.generate(Settings.defaults().withValidate(false));
        assertTrue(week.zoneHours().stream().anyMatch(ZoneHour::hotspot));
    }

    // ------------------------------------------------------------------ salida en disco

    @Test
    void outputUsesDataLakePartitions(@TempDir Path tmp) throws IOException {
        Path out = tmp.resolve("out");
        ObjectNode summary = OutputWriter.write(result, out);
        Pattern layout = Pattern.compile("raw/year=\\d{4}/month=\\d{2}/day=\\d{2}/source=[a-z-]+/events\\.jsonl$");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(out.resolve("raw"))) {
            files = walk.filter(p -> p.getFileName().toString().equals("events.jsonl")).toList();
        }
        assertFalse(files.isEmpty());
        int total = 0;
        for (Path file : files) {
            String relative = out.relativize(file).toString().replace('\\', '/');
            assertTrue(layout.matcher(relative).find(), relative);
            for (String line : Files.readAllLines(file)) {
                ObjectNode event = (ObjectNode) MAPPER.readTree(line);
                String expected = OutputWriter.rawPartition(event).toString().replace('\\', '/') + "/events.jsonl";
                assertEquals(expected, relative);
                total++;
            }
        }
        assertEquals(events.size(), total);
        assertEquals(events.size(), summary.get("events").get("total").asInt());
        assertTrue(Files.isRegularFile(out.resolve("seed").resolve("vehicles.json")));
        assertTrue(Files.isRegularFile(out.resolve("ground_truth_zone_hours.csv")));
    }

    @Test
    void refusesToDeleteFoldersItDidNotCreate(@TempDir Path tmp) throws IOException {
        Path folder = tmp.resolve("important");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("my-homework.txt"), "no me borres");
        assertThrows(UnsafeOutputDirException.class, () -> OutputWriter.write(result, folder));
        assertTrue(Files.isRegularFile(folder.resolve("my-homework.txt")));
    }

    @Test
    void canOverwriteItsOwnPreviousOutput(@TempDir Path tmp) {
        Path out = tmp.resolve("out");
        OutputWriter.write(result, out);
        OutputWriter.write(result, out);
        assertTrue(Files.isRegularFile(out.resolve(OutputWriter.MARKER)));
    }
}
