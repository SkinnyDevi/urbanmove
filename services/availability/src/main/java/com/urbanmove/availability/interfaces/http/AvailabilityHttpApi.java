package com.urbanmove.availability.interfaces.http;

import static com.urbanmove.availability.interfaces.http.Role.ADMIN;
import static com.urbanmove.availability.interfaces.http.Role.MAINTENANCE;
import static com.urbanmove.availability.interfaces.http.Role.USER;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.availability.application.VehicleNotFoundException;
import com.urbanmove.availability.infrastructure.logging.JsonLogEncoder;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;
import io.javalin.security.RouteRole;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Servidor HTTP del servicio (adaptador primario). */
public final class AvailabilityHttpApi {
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String PROBLEM_JSON = "application/problem+json";

    private static final Logger log = LoggerFactory.getLogger(AvailabilityHttpApi.class);
    private static final String USER_ATTRIBUTE = "user";
    private static final String CORRELATION = "correlationId";

    private AvailabilityHttpApi() {
    }

    public static Javalin create(ObjectMapper json, JwtVerifier jwt, HealthCheckHandler health, VehicleHandlers vehicles) {
        return Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(json, false));
            config.http.prefer405over404 = true;
            config.startup.showJavalinBanner = false;

            config.routes.before(AvailabilityHttpApi::assignCorrelationId);
            config.routes.beforeMatched(ctx -> authorize(ctx, jwt));
            config.routes.after(ctx -> MDC.remove(JsonLogEncoder.CORRELATION_ID));

            config.routes.get("/health", health);
            config.routes.get("/availability/vehicles", vehicles::listVehicles, USER, MAINTENANCE, ADMIN);
            config.routes.get("/availability/vehicles/{vehicleId}", vehicles::getVehicle, USER, MAINTENANCE, ADMIN);
            config.routes.get("/availability/zones/summary", vehicles::zoneSummary, MAINTENANCE, ADMIN);

            config.routes.exception(ApiProblem.class, (e, ctx) ->
                    problem(ctx, e.status(), e.type(), e.getMessage(), e.extensions()));
            config.routes.exception(VehicleNotFoundException.class, (e, ctx) ->
                    problem(ctx, HttpStatus.NOT_FOUND, "/problems/not-found", e.getMessage(), Map.of()));
            config.routes.exception(IllegalArgumentException.class, (e, ctx) ->
                    problem(ctx, HttpStatus.BAD_REQUEST, "/problems/validation", e.getMessage(), Map.of()));
            config.routes.exception(HttpResponseException.class, (e, ctx) -> {
                HttpStatus status = HttpStatus.forStatus(e.getStatus());
                problem(ctx, status, "/problems/" + status.name().toLowerCase().replace('_', '-'), e.getMessage(), Map.of());
            });
            config.routes.exception(Exception.class, (e, ctx) -> {
                log.error("Unexpected error", e);
                problem(ctx, HttpStatus.INTERNAL_SERVER_ERROR, "/problems/internal", "Unexpected error", Map.of());
            });
        });
    }

    static JwtVerifier.AuthenticatedUser user(Context ctx) {
        return ctx.attribute(USER_ATTRIBUTE);
    }

    private static void assignCorrelationId(Context ctx) {
        String correlationId = validOrNew(ctx.header(CORRELATION_HEADER));
        MDC.put(JsonLogEncoder.CORRELATION_ID, correlationId);
        ctx.attribute(CORRELATION, correlationId);
        ctx.header(CORRELATION_HEADER, correlationId);
    }

    private static String validOrNew(String header) {
        if (header != null) {
            try {
                return UUID.fromString(header.trim()).toString();
            } catch (IllegalArgumentException ignored) {
            }
        }
        return UUID.randomUUID().toString();
    }

    private static void authorize(Context ctx, JwtVerifier jwt) {
        Set<RouteRole> allowed = ctx.routeRoles();
        if (allowed.isEmpty()) {
            return;
        }
        JwtVerifier.AuthenticatedUser user = jwt.verify(ctx.header("Authorization"));
        if (Collections.disjoint(allowed, user.roles())) {
            throw ApiProblem.forbidden();
        }
        ctx.attribute(USER_ATTRIBUTE, user);
    }

    private static void problem(Context ctx, HttpStatus status, String type, String detail, Map<String, Object> extra) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", status.getMessage());
        body.put("status", status.getCode());
        if (detail != null && !detail.isBlank()) {
            body.put("detail", detail);
        }
        body.put("instance", ctx.path());
        String correlationId = ctx.attribute(CORRELATION);
        body.put("correlationId", correlationId != null ? correlationId : UUID.randomUUID().toString());
        body.putAll(extra);
        ctx.status(status).json(body).contentType(PROBLEM_JSON);
    }
}
