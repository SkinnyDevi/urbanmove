package com.urbanmove.maintenance.interfaces.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.urbanmove.maintenance.interfaces.http.ApiProblem.FieldError;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Lectura y validación de las peticiones según docs/contracts/openapi/maintenance.yaml
 * (AGENTS.md 3.1). Acumula todos los errores para devolverlos juntos en un 400.
 */
final class RequestParser {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final ObjectMapper json;

    RequestParser(ObjectMapper json) {
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Cabecera Idempotency-Key obligatoria, UUID. */
    static String idempotencyKey(Context ctx) {
        String header = ctx.header(IDEMPOTENCY_HEADER);
        if (header == null || header.isBlank()) {
            throw ApiProblem.validation(IDEMPOTENCY_HEADER, "is required");
        }
        try {
            return UUID.fromString(header.trim()).toString();
        } catch (IllegalArgumentException e) {
            throw ApiProblem.validation(IDEMPOTENCY_HEADER, "must be a UUID");
        }
    }

    /** Cuerpo JSON con exactamente los campos permitidos (additionalProperties: false). */
    Body body(Context ctx, Set<String> allowed) {
        JsonNode node;
        try {
            node = ctx.body().isBlank() ? null : json.readTree(ctx.body());
        } catch (Exception e) {
            throw ApiProblem.validation("body", "is not valid JSON");
        }
        if (node == null || node.getNodeType() != JsonNodeType.OBJECT) {
            throw ApiProblem.validation("body", "must be a JSON object");
        }
        Body body = new Body(node);
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) {
                body.errors.add(new FieldError(name, "is not allowed"));
            }
        });
        return body;
    }

    /** Campos de un cuerpo JSON, con sus errores acumulados. */
    static final class Body {

        private final JsonNode node;
        private final List<FieldError> errors = new ArrayList<>();

        private Body(JsonNode node) {
            this.node = node;
        }

        <T> T required(String field, Function<String, T> parser) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                errors.add(new FieldError(field, "is required"));
                return null;
            }
            return parse(field, value, parser);
        }

        <T> T optional(String field, Function<String, T> parser) {
            JsonNode value = node.get(field);
            return value == null || value.isNull() ? null : parse(field, value, parser);
        }

        private <T> T parse(String field, JsonNode value, Function<String, T> parser) {
            if (!value.isTextual()) {
                errors.add(new FieldError(field, "must be a string"));
                return null;
            }
            try {
                return parser.apply(value.asText());
            } catch (IllegalArgumentException e) {
                errors.add(new FieldError(field, e.getMessage() == null ? "is not valid" : e.getMessage()));
                return null;
            }
        }

        /** Lanza un 400 con todos los errores, si los hay. */
        void validate() {
            if (!errors.isEmpty()) {
                throw ApiProblem.validation(errors);
            }
        }
    }

    /** Valor de un enumerado, con un mensaje que lista los valores permitidos. */
    static <E extends Enum<E>> Function<String, E> enumValue(Class<E> type) {
        return text -> {
            try {
                return Enum.valueOf(type, text);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("must be one of " + List.of(type.getEnumConstants()));
            }
        };
    }

    /** Texto de longitud limitada. */
    static Function<String, String> text(int minLength, int maxLength) {
        return text -> {
            if (text.length() < minLength || text.length() > maxLength) {
                throw new IllegalArgumentException("length must be between " + minLength + " and " + maxLength);
            }
            return text;
        };
    }
}
