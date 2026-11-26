package com.urbanmove.relocation.interfaces.http;

import com.urbanmove.relocation.application.ConcurrentUpdateException;
import com.urbanmove.relocation.application.IdempotencyKeyReusedException;
import com.urbanmove.relocation.application.OrderNotFoundException;
import com.urbanmove.relocation.domain.InvalidRelocationException;
import com.urbanmove.relocation.domain.InvalidStateTransitionError;
import com.urbanmove.relocation.domain.NotAssigneeException;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.HttpStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Convierte cada excepción en una respuesta RFC 7807 con correlationId. */
final class ProblemHandlers {
    private static final Logger log = LoggerFactory.getLogger(ProblemHandlers.class);
    private static final String PROBLEM_JSON = "application/problem+json";

    private ProblemHandlers() {
    }

    static void register(RoutesConfig routes) {
        routes.exception(ApiProblem.class, (e, ctx) -> write(ctx, e.status(), e.type(), e.getMessage(), e.extensions()));
        routes.exception(OrderNotFoundException.class, (e, ctx) ->
                write(ctx, HttpStatus.NOT_FOUND, "/problems/not-found", e.getMessage(), Map.of()));
        routes.exception(InvalidStateTransitionError.class, (e, ctx) ->
                write(ctx, HttpStatus.CONFLICT, "/problems/invalid-state-transition", e.getMessage(),
                        Map.of("currentState", e.from().name(), "action", e.action().name())));
        routes.exception(NotAssigneeException.class, (e, ctx) ->
                write(ctx, HttpStatus.FORBIDDEN, "/problems/not-assignee", e.getMessage(), Map.of()));
        routes.exception(InvalidRelocationException.class, (e, ctx) ->
                write(ctx, HttpStatus.BAD_REQUEST, "/problems/validation", e.getMessage(), Map.of()));
        routes.exception(ConcurrentUpdateException.class, (e, ctx) ->
                write(ctx, HttpStatus.CONFLICT, "/problems/concurrent-update", e.getMessage(), Map.of()));
        routes.exception(IdempotencyKeyReusedException.class, (e, ctx) ->
                write(ctx, HttpStatus.CONFLICT, "/problems/idempotency-key-reused", e.getMessage(), Map.of()));
        routes.exception(IllegalArgumentException.class, (e, ctx) ->
                write(ctx, HttpStatus.BAD_REQUEST, "/problems/validation", e.getMessage(), Map.of()));
        routes.exception(HttpResponseException.class, (e, ctx) -> {
            HttpStatus status = HttpStatus.forStatus(e.getStatus());
            write(ctx, status, "/problems/" + status.name().toLowerCase().replace('_', '-'), e.getMessage(), Map.of());
        });
        routes.exception(Exception.class, (e, ctx) -> {
            log.error("Unexpected error", e);
            write(ctx, HttpStatus.INTERNAL_SERVER_ERROR, "/problems/internal", "Unexpected error", Map.of());
        });
    }

    private static void write(Context ctx, HttpStatus status, String type, String detail, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", status.getMessage());
        body.put("status", status.getCode());
        if (detail != null && !detail.isBlank()) {
            body.put("detail", detail);
        }
        body.put("instance", ctx.path());
        body.put("correlationId", RequestContext.correlationId(ctx));
        body.putAll(extra);
        ctx.status(status).json(body).contentType(PROBLEM_JSON);
    }
}
