package com.urbanmove.synthgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;

/** Construye summary.json: cuántos eventos, viajes, reservas, averías, déficits... */
public final class SummaryBuilder {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private SummaryBuilder() {
    }

    public static ObjectNode build(GenerationResult result) {
        Settings s = result.settings();
        Rules r = result.rules();
        List<ObjectNode> events = result.events();
        Map<String, Long> byType = count(events, e -> e.get("eventType").asText());
        List<JsonNode> rides = payloadsOf(events, "RideFinished");
        List<JsonNode> returns = payloadsOf(events, "VehicleReturnedToService");

        ObjectNode summary = JSON.objectNode();
        ObjectNode parameters = summary.putObject("parameters");
        parameters.put("startDate", s.startDate().toString()).put("days", s.days()).put("seed", s.seed())
                .put("vehicles", s.vehicles()).put("users", s.users()).put("technicians", s.technicians())
                .put("tripsPerDay", s.tripsPerDay()).put("validated", s.validate());

        summary.putObject("period")
                .put("firstEvent", events.get(0).get("timestamp").asText())
                .put("lastEvent", events.get(events.size() - 1).get("timestamp").asText());

        ObjectNode eventsNode = summary.putObject("events");
        eventsNode.put("total", events.size());
        eventsNode.set("byType", toNode(byType));
        eventsNode.set("bySource", toNode(count(events, e -> e.get("source").asText())));
        eventsNode.set("byDay", toNode(count(events, e -> e.get("timestamp").asText().substring(0, 10))));
        ArrayNode notGenerated = eventsNode.putArray("notGenerated");
        notGenerated.add("DemandHotspotDetected").add("RelocationOrderCreated").add("RelocationOrderCompleted");

        summary.putObject("reservations")
                .put("created", byType.getOrDefault("VehicleReserved", 0L))
                .put("cancelled", byType.getOrDefault("ReservationCancelled", 0L))
                .put("expired", byType.getOrDefault("ReservationExpired", 0L));

        ObjectNode ridesNode = summary.putObject("rides");
        ridesNode.put("total", rides.size())
                .put("avgDurationMinutes", average(rides, p -> p.get("durationSeconds").asDouble() / 60))
                .put("avgDistanceKm", average(rides, p -> p.get("distanceKm").asDouble()))
                .put("avgCostEur", average(rides, p -> p.get("preliminaryCost").get("amount").asDouble()));
        ArrayNode top = ridesNode.putArray("topOriginDestination");
        count(rides, p -> p.get("originZoneId").asText() + ">" + p.get("destinationZoneId").asText())
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(5)
                .forEach(entry -> {
                    String[] od = entry.getKey().split(">");
                    top.addObject().put("origin", od[0]).put("destination", od[1]).put("rides", entry.getValue());
                });

        ObjectNode maintenance = summary.putObject("maintenance");
        maintenance.put("batteryLow", byType.getOrDefault("VehicleBatteryLow", 0L));
        maintenance.set("withdrawalsByReason", toNode(count(payloadsOf(events, "VehicleWithdrawnForMaintenance"),
                p -> p.get("reason").asText())));
        maintenance.put("avgOutOfServiceHours", average(returns, p -> p.get("outOfServiceSeconds").asDouble() / 3600));

        Map<String, Integer> unmetByZone = result.unmetDemand().entrySet().stream()
                .collect(Collectors.groupingBy(e -> e.getKey().zoneId(), Collectors.summingInt(Map.Entry::getValue)));
        ObjectNode unmetNode = summary.putObject("unmetDemand");
        unmetNode.put("note", "Peticiones que no encontraron vehículo. No hay evento para esto: "
                + "es información de referencia.");
        unmetNode.put("total", unmetByZone.values().stream().mapToInt(Integer::intValue).sum());
        ObjectNode unmetZones = unmetNode.putObject("byZone");
        unmetByZone.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .forEach(e -> unmetZones.put(e.getKey(), e.getValue()));

        summary.put("hotspotRule", "availableAtEnd < " + r.hotspotMaxAvailable + " y demandSignals > "
                + r.hotspotMinSignals + "; demandSignals = VehicleReserved + RideStarted sin reserva en esa hora y zona");
        ArrayNode hotspots = summary.putArray("hotspotCandidates");
        result.zoneHours().stream().filter(ZoneHour::hotspot).forEach(h -> hotspots.addObject()
                .put("hourStart", h.hourStart()).put("zoneId", h.zoneId())
                .put("availableAtEnd", h.availableAtEnd()).put("demandSignals", h.demandSignals())
                .put("unmetRequests", h.unmetRequests()));
        return summary;
    }

    private static List<JsonNode> payloadsOf(List<ObjectNode> events, String eventType) {
        return events.stream()
                .filter(e -> eventType.equals(e.get("eventType").asText()))
                .map(e -> e.get("payload"))
                .toList();
    }

    private static <T> Map<String, Long> count(List<T> items, Function<T, String> key) {
        return items.stream().collect(Collectors.groupingBy(key, TreeMap::new, Collectors.counting()));
    }

    private static ObjectNode toNode(Map<String, Long> counts) {
        ObjectNode node = JSON.objectNode();
        counts.forEach(node::put);
        return node;
    }

    private static double average(List<JsonNode> items, ToDoubleFunction<JsonNode> value) {
        double avg = items.stream().mapToDouble(value).average().orElse(0);
        return World.round(avg, 2);
    }
}
