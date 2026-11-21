package com.urbanmove.zones.unit;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.zones.infrastructure.logging.JsonLogEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;

/** El formato de log es el de AGENTS.md 6.2. */
class JsonLogEncoderTest {

    private final JsonLogEncoder encoder = new JsonLogEncoder();

    {
        encoder.setService("zones-service");
    }

    private static LoggingEvent event(String message, Map<String, String> mdc) {
        Logger logger = new LoggerContext().getLogger("test.Logger");
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.INFO, message, null, null);
        event.setMDCPropertyMap(mdc);
        event.setTimeStamp(1_763_640_130_123L);
        return event;
    }

    private String encode(LoggingEvent event) {
        return new String(encoder.encode(event), StandardCharsets.UTF_8);
    }

    private static JsonNode parse(String line) throws Exception {
        return new ObjectMapper().readTree(line);
    }

    @Test
    void writesOneJsonLineWithTheCommonFields() throws Exception {
        LoggingEvent event = event("Zone seed applied", Map.of("correlationId", "c9a8b7d6-0000-4000-8000-000000000001"));
        event.setKeyValuePairs(List.of(new KeyValuePair("zonesInserted", 8), new KeyValuePair("zoneId", "tafira")));

        String line = encode(event);

        assertTrue(line.endsWith("\n"));
        assertEquals(1, line.strip().lines().count());
        JsonNode json = parse(line);
        assertEquals("2025-11-20T12:02:10.123Z", json.get("timestamp").asText());
        assertEquals("INFO", json.get("level").asText());
        assertEquals("zones-service", json.get("service").asText());
        assertEquals("c9a8b7d6-0000-4000-8000-000000000001", json.get("correlationId").asText());
        assertEquals("Zone seed applied", json.get("message").asText());
        assertEquals(8, json.get("context").get("zonesInserted").asInt());
        assertEquals("tafira", json.get("context").get("zoneId").asText());
        assertFalse(json.get("context").has("correlationId"), "correlationId va arriba, no en context");
    }

    @Test
    void omitsCorrelationIdContextAndErrorWhenThereAreNone() throws Exception {
        JsonNode json = parse(encode(event("Started", Map.of())));

        assertFalse(json.has("correlationId"));
        assertFalse(json.has("context"));
        assertFalse(json.has("error"));
    }

    @Test
    void includesTheStackTraceOfErrors() throws Exception {
        Logger logger = new LoggerContext().getLogger("test.Logger");
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.ERROR, "Boom", new IllegalStateException("db down"), null);
        event.setMDCPropertyMap(Map.of());

        JsonNode json = parse(encode(event));

        assertEquals("ERROR", json.get("level").asText());
        assertTrue(json.get("error").asText().contains("IllegalStateException: db down"));
    }
}
