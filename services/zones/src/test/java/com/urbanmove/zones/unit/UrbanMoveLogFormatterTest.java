package com.urbanmove.zones.unit;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.urbanmove.zones.infrastructure.logging.UrbanMoveLogFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.event.KeyValuePair;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** El formato de log es el de AGENTS.md 6.2. */
class UrbanMoveLogFormatterTest {

    private final UrbanMoveLogFormatter formatter =
            new UrbanMoveLogFormatter(new MockEnvironment().withProperty("spring.application.name", "zones-service"));

    private static LoggingEvent event(String message, Map<String, String> mdc) {
        Logger logger = new LoggerContext().getLogger("test.Logger");
        LoggingEvent event = new LoggingEvent("fqcn", logger, Level.INFO, message, null, null);
        event.setMDCPropertyMap(mdc);
        event.setTimeStamp(1_763_640_130_123L);
        return event;
    }

    private static JsonNode parse(String line) {
        return JsonMapper.builder().build().readTree(line);
    }

    @Test
    void writesOneJsonLineWithTheCommonFields() {
        LoggingEvent event = event("Zone seed applied", Map.of("correlationId", "c9a8b7d6-0000-4000-8000-000000000001"));
        event.setKeyValuePairs(List.of(new KeyValuePair("zonesInserted", 8)));

        String line = formatter.format(event);

        assertTrue(line.endsWith("\n"));
        assertEquals(1, line.strip().lines().count());
        JsonNode json = parse(line);
        assertEquals("2025-11-20T12:02:10.123Z", json.get("timestamp").asString());
        assertEquals("INFO", json.get("level").asString());
        assertEquals("zones-service", json.get("service").asString());
        assertEquals("c9a8b7d6-0000-4000-8000-000000000001", json.get("correlationId").asString());
        assertEquals("Zone seed applied", json.get("message").asString());
        assertEquals(8, json.get("context").get("zonesInserted").asInt());
        assertFalse(json.get("context").has("correlationId"), "correlationId va arriba, no en context");
    }

    @Test
    void omitsCorrelationIdAndContextWhenThereAreNone() {
        JsonNode json = parse(formatter.format(event("Started", Map.of())));

        assertFalse(json.has("correlationId"));
        assertFalse(json.has("context"));
        assertFalse(json.has("error"));
    }
}
