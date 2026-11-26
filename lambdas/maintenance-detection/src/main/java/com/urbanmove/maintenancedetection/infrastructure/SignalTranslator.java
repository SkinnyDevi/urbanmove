package com.urbanmove.maintenancedetection.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.maintenancedetection.application.IncomingSignal;
import com.urbanmove.maintenancedetection.application.SignalMetadata;
import com.urbanmove.maintenancedetection.domain.VehicleId;
import com.urbanmove.maintenancedetection.domain.VehicleSignal;
import com.urbanmove.maintenancedetection.domain.VehicleType;
import com.urbanmove.maintenancedetection.domain.ZoneId;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Traduce el JSON de un evento del bus a la señal que entiende la Lambda. */
public final class SignalTranslator {
    private final ObjectMapper json;

    public SignalTranslator(ObjectMapper json) {
        this.json = Objects.requireNonNull(json);
    }

    public static final class MalformedEventException extends RuntimeException {
        public MalformedEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Optional<IncomingSignal> translate(String body) {
        try {
            JsonNode root = json.readTree(body);
            String type = text(root, "eventType");
            if (!"VehicleBatteryLow".equals(type)) {
                return Optional.empty();
            }
            JsonNode p = root.path("payload");
            VehicleSignal signal = new VehicleSignal.BatteryLow(new VehicleId(text(p, "vehicleId")),
                    VehicleType.valueOf(text(p, "vehicleType")), new ZoneId(text(p, "zoneId")),
                    integer(p, "batteryLevel"), integer(p, "threshold"));
            return Optional.of(new IncomingSignal(new SignalMetadata(UUID.fromString(text(root, "eventId")), type,
                    text(root, "correlationId")), signal));
        } catch (MalformedEventException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new MalformedEventException("Malformed event: " + e.getMessage(), e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new MalformedEventException("Missing field '" + field + "'", null);
        }
        return value.asText();
    }

    private static int integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new MalformedEventException("Missing number '" + field + "'", null);
        }
        return value.asInt();
    }
}
