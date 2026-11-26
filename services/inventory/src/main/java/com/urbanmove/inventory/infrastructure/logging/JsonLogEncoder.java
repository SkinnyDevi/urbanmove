package com.urbanmove.inventory.infrastructure.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.event.KeyValuePair;

/** Formato de log JSON común: timestamp, level, service, correlationId, message y context. */
public final class JsonLogEncoder extends EncoderBase<ILoggingEvent> {
    public static final String CORRELATION_ID = "correlationId";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final byte[] NO_BYTES = new byte[0];

    private String service = "unknown-service";

    public void setService(String service) {
        this.service = service;
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        line.put("level", event.getLevel().toString());
        line.put("service", service);
        String correlationId = event.getMDCPropertyMap().get(CORRELATION_ID);
        if (correlationId != null) {
            line.put(CORRELATION_ID, correlationId);
        }
        line.put("message", event.getFormattedMessage());
        line.put("logger", event.getLoggerName());
        Map<String, Object> context = context(event);
        if (!context.isEmpty()) {
            line.put("context", context);
        }
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            line.put("error", ThrowableProxyUtil.asString(throwable));
        }
        try {
            return (JSON.writeValueAsString(line) + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            return ("{\"level\":\"ERROR\",\"service\":\"" + service + "\",\"message\":\"Unserializable log event\"}\n")
                    .getBytes(StandardCharsets.UTF_8);
        }
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
                Object value = pair.value;
                boolean plain = value == null || value instanceof Number || value instanceof Boolean;
                context.put(pair.key, plain ? value : String.valueOf(value));
            }
        }
        return context;
    }

    @Override
    public byte[] headerBytes() {
        return NO_BYTES;
    }

    @Override
    public byte[] footerBytes() {
        return NO_BYTES;
    }
}
