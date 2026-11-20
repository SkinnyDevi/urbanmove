package com.urbanmove.zones.infrastructure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.json.JsonWriter;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import org.springframework.core.env.Environment;

/**
 * Formato de log JSON común de UrbanMove (AGENTS.md 6.2): una línea por evento con
 * timestamp, level, service, correlationId, message y context.
 *
 * <p>{@code context} reúne el MDC (salvo correlationId) y los pares clave-valor del log, p. ej.
 * {@code log.atInfo().addKeyValue("zoneId", id).log("...")}. Se activa con
 * {@code logging.structured.format.console} en application.yml.
 */
public final class UrbanMoveLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    public static final String CORRELATION_ID = "correlationId";

    private final JsonWriter<ILoggingEvent> writer;

    public UrbanMoveLogFormatter(Environment environment) {
        String service = environment.getProperty("spring.application.name", "unknown-service");
        this.writer = JsonWriter.<ILoggingEvent>of(members -> {
            members.add("timestamp", event -> Instant.ofEpochMilli(event.getTimeStamp()).toString());
            members.add("level", event -> event.getLevel().toString());
            members.add("service", service);
            members.add(CORRELATION_ID, event -> event.getMDCPropertyMap().get(CORRELATION_ID)).whenNotNull();
            members.add("message", ILoggingEvent::getFormattedMessage);
            members.add("logger", ILoggingEvent::getLoggerName);
            members.add("context", UrbanMoveLogFormatter::context).whenNotEmpty();
            members.add("error", UrbanMoveLogFormatter::error).whenNotNull();
        }).withNewLineAtEnd();
    }

    @Override
    public String format(ILoggingEvent event) {
        return writer.writeToString(event);
    }

    private static Map<String, Object> context(ILoggingEvent event) {
        Map<String, Object> context = new LinkedHashMap<>();
        event.getMDCPropertyMap().forEach((key, value) -> {
            if (!CORRELATION_ID.equals(key)) {
                context.put(key, value);
            }
        });
        if (event.getKeyValuePairs() != null) {
            for (KeyValuePair pair : event.getKeyValuePairs()) {
                context.put(pair.key, pair.value);
            }
        }
        return context;
    }

    private static String error(ILoggingEvent event) {
        IThrowableProxy throwable = event.getThrowableProxy();
        return throwable == null ? null : ThrowableProxyUtil.asString(throwable);
    }
}
