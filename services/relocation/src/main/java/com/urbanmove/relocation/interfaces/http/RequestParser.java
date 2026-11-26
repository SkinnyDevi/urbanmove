package com.urbanmove.relocation.interfaces.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.urbanmove.relocation.interfaces.http.ApiProblem.FieldError;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Lectura y validación de las peticiones según docs/contracts/openapi/relocation.yaml. */
final class RequestParser {
    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final ObjectMapper json;

    RequestParser(ObjectMapper json) {
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

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

        <T> List<T> requiredList(String field, Function<String, T> parser) {
            JsonNode value = node.get(field);
            if (value == null || !value.isArray() || value.isEmpty()) {
                errors.add(new FieldError(field, "must be a non-empty array"));
                return List.of();
            }
            List<T> items = new ArrayList<>();
            for (int i = 0; i < value.size(); i++) {
                T item = parse(field + "[" + i + "]", value.get(i), parser);
                if (item != null) {
                    items.add(item);
                }
            }
            return items;
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

        void validate() {
            if (!errors.isEmpty()) {
                throw ApiProblem.validation(errors);
            }
        }
    }

    static <E extends Enum<E>> Function<String, E> enumValue(Class<E> type) {
        return text -> {
            try {
                return Enum.valueOf(type, text);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("must be one of " + List.of(type.getEnumConstants()));
            }
        };
    }

    static Function<String, String> text(int minLength, int maxLength) {
        return text -> {
            if (text.length() < minLength || text.length() > maxLength) {
                throw new IllegalArgumentException("length must be between " + minLength + " and " + maxLength);
            }
            return text;
        };
    }
}
