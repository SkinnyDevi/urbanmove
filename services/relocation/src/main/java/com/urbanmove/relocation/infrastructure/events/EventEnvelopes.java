package com.urbanmove.relocation.infrastructure.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.relocation.application.port.OutgoingEvent;
import com.urbanmove.relocation.domain.Identifiers.VehicleId;
import com.urbanmove.relocation.domain.event.DomainEvent;
import com.urbanmove.relocation.domain.event.RelocationOrderCompleted;
import com.urbanmove.relocation.domain.event.RelocationOrderCreated;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Convierte un evento de dominio en el JSON del envelope común de los contratos AsyncAPI. */
public final class EventEnvelopes {
    public static final String SOURCE = "urbanmove.relocation";
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
            case RelocationOrderCreated e -> {
                payload.put("relocationOrderId", e.relocationOrderId().value());
                payload.put("sourceZoneId", e.sourceZoneId().value());
                payload.put("targetZoneId", e.targetZoneId().value());
                payload.put("vehicleType", e.vehicleType().name());
                payload.put("quantity", e.quantity());
                payload.put("strategy", e.strategy().name());
                if (e.triggeredByEventId() != null) {
                    payload.put("triggeredByEventId", e.triggeredByEventId().toString());
                }
            }
            case RelocationOrderCompleted e -> {
                payload.put("relocationOrderId", e.relocationOrderId().value());
                payload.put("sourceZoneId", e.sourceZoneId().value());
                payload.put("targetZoneId", e.targetZoneId().value());
                payload.put("vehicleType", e.vehicleType().name());
                payload.put("vehicleIds", e.vehicleIds().stream().map(VehicleId::value).toList());
                payload.put("createdAt", timestamp(e.createdAt()));
                payload.put("completedAt", timestamp(e.completedAt()));
                payload.put("delaySeconds", e.delaySeconds());
            }
        }
        return payload;
    }
}
