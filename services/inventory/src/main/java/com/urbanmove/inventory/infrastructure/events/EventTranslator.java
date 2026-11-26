package com.urbanmove.inventory.infrastructure.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.inventory.application.EventMetadata;
import com.urbanmove.inventory.application.IncomingEvent;
import com.urbanmove.inventory.application.InventoryEvent;
import com.urbanmove.inventory.domain.Money;
import com.urbanmove.inventory.domain.VehicleId;
import com.urbanmove.inventory.domain.VehicleType;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Traduce el JSON de un evento del bus a los hechos que entiende inventory. */
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
            Optional<InventoryEvent> event = toInventoryEvent(type, root.path("payload"));
            if (event.isEmpty()) {
                return Optional.empty();
            }
            EventMetadata meta = new EventMetadata(UUID.fromString(text(root, "eventId")), type,
                    text(root, "correlationId"), Instant.parse(text(root, "timestamp")));
            return Optional.of(new IncomingEvent(meta, event.get()));
        } catch (MalformedEventException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new MalformedEventException("Malformed event: " + e.getMessage(), e);
        }
    }

    private static Optional<InventoryEvent> toInventoryEvent(String type, JsonNode p) {
        InventoryEvent event = switch (type) {
            case "VehiclePurchased" -> new InventoryEvent.Purchased(vehicle(p), VehicleType.valueOf(text(p, "vehicleType")),
                    text(p, "model"), LocalDate.parse(text(p, "purchaseDate")), price(p.get("purchasePrice")));
            case "VehicleWithdrawnForMaintenance" -> new InventoryEvent.Withdrawn(vehicle(p));
            case "VehicleReturnedToService" -> new InventoryEvent.ReturnedToService(vehicle(p));
            default -> null;
        };
        return Optional.ofNullable(event);
    }

    private static Money price(JsonNode price) {
        if (price == null || price.isNull()) {
            return null;
        }
        if (!price.path("amount").isNumber()) {
            throw new MalformedEventException("purchasePrice.amount must be a number", null);
        }
        return new Money(new BigDecimal(price.get("amount").asText()), text(price, "currency"));
    }

    private static VehicleId vehicle(JsonNode payload) {
        return new VehicleId(text(payload, "vehicleId"));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new MalformedEventException("Missing field '" + field + "'", null);
        }
        return value.asText();
    }
}
