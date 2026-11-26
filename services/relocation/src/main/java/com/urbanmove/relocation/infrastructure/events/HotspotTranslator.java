package com.urbanmove.relocation.infrastructure.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.relocation.application.DemandHotspot;
import com.urbanmove.relocation.domain.RelocationStrategy;
import com.urbanmove.relocation.domain.VehicleType;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Traduce el JSON de un DemandHotspotDetected del bus al déficit que entiende relocation. */
public final class HotspotTranslator {
    private static final String HOTSPOT = "DemandHotspotDetected";

    private final ObjectMapper json;

    public HotspotTranslator(ObjectMapper json) {
        this.json = Objects.requireNonNull(json);
    }

    public static final class MalformedEventException extends RuntimeException {
        public MalformedEventException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public Optional<DemandHotspot> translate(String body) {
        try {
            JsonNode root = json.readTree(body);
            if (!HOTSPOT.equals(text(root, "eventType"))) {
                return Optional.empty();
            }
            JsonNode p = root.path("payload");
            return Optional.of(new DemandHotspot(
                    UUID.fromString(text(root, "eventId")),
                    text(root, "correlationId"),
                    Instant.parse(text(root, "timestamp")),
                    text(p, "zoneId"),
                    text(p, "suggestedSourceZoneId"),
                    VehicleType.valueOf(text(p, "vehicleType")),
                    integer(p, "suggestedQuantity"),
                    RelocationStrategy.valueOf(text(p, "strategy"))));
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
        if (value == null || !value.isIntegralNumber()) {
            throw new MalformedEventException("Missing integer field '" + field + "'", null);
        }
        return value.asInt();
    }
}
