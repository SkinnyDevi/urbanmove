package com.urbanmove.inventory.infrastructure.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.inventory.application.port.OutgoingEvent;
import com.urbanmove.inventory.domain.event.DomainEvent;
import com.urbanmove.inventory.domain.event.VehicleRetired;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Convierte un evento de dominio en el JSON del envelope común de los contratos AsyncAPI. */
public final class EventEnvelopes {
    public static final String SOURCE = "urbanmove.inventory";
    public static final String SCHEMA_VERSION = "1.0.0";
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final ObjectMapper json;

    public EventEnvelopes(ObjectMapper json) {
        this.json = Objects.requireNonNull(json);
    }

    public static String timestamp(Instant instant) {
        return TIMESTAMP.format(instant);
    }

    public String toJson(OutgoingEvent outgoing) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", outgoing.eventId().toString());
        envelope.put("timestamp", timestamp(outgoing.event().occurredAt()));
        envelope.put("source", SOURCE);
        envelope.put("eventType", outgoing.event().eventType());
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("correlationId", outgoing.correlationId());
        envelope.put("payload", payload(outgoing.event()));
        try {
            return json.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + outgoing.eventId(), e);
        }
    }

    private static Map<String, Object> payload(DomainEvent event) {
        Map<String, Object> payload = new LinkedHashMap<>();
        switch (event) {
            case VehicleRetired e -> {
                payload.put("vehicleId", e.vehicleId().value());
                payload.put("vehicleType", e.vehicleType().name());
                payload.put("reason", e.reason());
            }
        }
        return payload;
    }
}
