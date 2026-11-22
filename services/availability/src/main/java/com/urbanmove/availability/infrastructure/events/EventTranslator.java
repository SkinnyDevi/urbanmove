package com.urbanmove.availability.infrastructure.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.availability.application.EventMetadata;
import com.urbanmove.availability.application.IncomingEvent;
import com.urbanmove.availability.application.VehicleEvent;
import com.urbanmove.availability.domain.VehicleId;
import com.urbanmove.availability.domain.VehicleType;
import com.urbanmove.availability.domain.ZoneId;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Traduce el JSON de un evento del bus a los hechos que entiende availability. */
public final class EventTranslator {
    private final ObjectMapper json;

    public EventTranslator(ObjectMapper json) {
        this.json = Objects.requireNonNull(json);
    }

    public static final class MalformedEventException extends RuntimeException {
        public MalformedEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Optional<IncomingEvent> translate(String body) {
        try {
            JsonNode root = json.readTree(body);
            String type = text(root, "eventType");
            Optional<VehicleEvent> event = toVehicleEvent(type, root.path("payload"));
            if (event.isEmpty()) {
                return Optional.empty();
            }
            EventMetadata meta = new EventMetadata(
                    UUID.fromString(text(root, "eventId")),
                    type,
                    text(root, "correlationId"),
                    Instant.parse(text(root, "timestamp")));
            return Optional.of(new IncomingEvent(meta, event.get()));
        } catch (MalformedEventException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new MalformedEventException("Malformed event: " + e.getMessage(), e);
        }
    }

    private static Optional<VehicleEvent> toVehicleEvent(String type, JsonNode p) {
        VehicleEvent event = switch (type) {
            case "VehiclePurchased" -> new VehicleEvent.Purchased(vehicle(p), VehicleType.valueOf(text(p, "vehicleType")),
                    zone(p, "initialZoneId"));
            case "VehicleReserved" -> new VehicleEvent.Reserved(vehicle(p));
            case "ReservationCancelled", "ReservationExpired" -> new VehicleEvent.ReservationReleased(vehicle(p));
            case "RideStarted" -> new VehicleEvent.RideStarted(vehicle(p));
            case "RideFinished" -> new VehicleEvent.RideFinished(vehicle(p), zone(p, "destinationZoneId"));
            case "VehicleBatteryLow" -> new VehicleEvent.BatteryLow(vehicle(p));
            case "VehicleWithdrawnForMaintenance" -> new VehicleEvent.WithdrawnForMaintenance(vehicle(p));
            case "VehicleReturnedToService" -> new VehicleEvent.ReturnedToService(vehicle(p), zone(p, "zoneId"));
            case "RelocationOrderCompleted" -> new VehicleEvent.Relocated(vehicleList(p), zone(p, "targetZoneId"));
            default -> null;
        };
        return Optional.ofNullable(event);
    }

    private static VehicleId vehicle(JsonNode payload) {
        return new VehicleId(text(payload, "vehicleId"));
    }

    private static ZoneId zone(JsonNode payload, String field) {
        return new ZoneId(text(payload, field));
    }

    private static List<VehicleId> vehicleList(JsonNode payload) {
        JsonNode ids = payload.get("vehicleIds");
        if (ids == null || !ids.isArray() || ids.isEmpty()) {
            throw new MalformedEventException("Missing vehicleIds", null);
        }
        List<VehicleId> result = new ArrayList<>();
        ids.forEach(id -> result.add(new VehicleId(id.asText())));
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new MalformedEventException("Missing field '" + field + "'", null);
        }
        return value.asText();
    }
}
