package com.urbanmove.zones.interfaces.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.zones.application.ZoneNotFoundException;
import com.urbanmove.zones.domain.InvalidZoneException;
import com.urbanmove.zones.infrastructure.logging.JsonLogEncoder;
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
public final class ZonesHttpApi {
    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String PROBLEM_JSON = "application/problem+json";

    private static final Logger log = LoggerFactory.getLogger(ZonesHttpApi.class);
    private static final RouteRole[] ALL_ROLES = {Role.USER, Role.MAINTENANCE, Role.ADMIN};

    private ZonesHttpApi() {
    }

    public static Javalin create(ObjectMapper json, JwtVerifier jwt, HealthCheckHandler health, ZoneHandlers zones) {
        return Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(json, false));
            config.http.prefer405over404 = true;
            config.startup.showJavalinBanner = false;

            config.routes.before(ZonesHttpApi::assignCorrelationId);
            config.routes.beforeMatched(ctx -> authorize(ctx, jwt));
            config.routes.after(ctx -> MDC.remove(JsonLogEncoder.CORRELATION_ID));

            config.routes.get("/health", health);
            config.routes.get("/zones", zones::listZones, ALL_ROLES);
            config.routes.get("/zones/{zoneId}", zones::getZone, ALL_ROLES);

            config.routes.exception(ApiProblem.class, (e, ctx) -> problem(ctx, e.status(), e.type(), e.getMessage()));
            config.routes.exception(ZoneNotFoundException.class, (e, ctx) -> {
                log.atInfo().addKeyValue("zoneId", e.zoneId().value()).log("Zone not found");
                problem(ctx, HttpStatus.NOT_FOUND, "/problems/not-found", e.getMessage());
            });
            config.routes.exception(InvalidZoneException.class, (e, ctx) ->
                    problem(ctx, HttpStatus.BAD_REQUEST, "/problems/validation", e.getMessage()));

            config.routes.exception(HttpResponseException.class, (e, ctx) -> {
                HttpStatus status = HttpStatus.forStatus(e.getStatus());
                problem(ctx, status, "/problems/" + status.name().toLowerCase().replace('_', '-'), e.getMessage());
            });
            config.routes.exception(Exception.class, (e, ctx) -> {
                log.error("Unexpected error", e);
                problem(ctx, HttpStatus.INTERNAL_SERVER_ERROR, "/problems/internal", "Unexpected error");
            });
        });
    }

    private static void assignCorrelationId(Context ctx) {
        String correlationId = validOrNew(ctx.header(CORRELATION_HEADER));
        MDC.put(JsonLogEncoder.CORRELATION_ID, correlationId);
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
        ctx.attribute("user", user);
    }

    private static void problem(Context ctx, HttpStatus status, String type, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("title", status.getMessage());
        body.put("status", status.getCode());
        if (detail != null && !detail.isBlank()) {
            body.put("detail", detail);
        }
        body.put("instance", ctx.path());
        body.put("correlationId", ctx.res().getHeader(CORRELATION_HEADER));
        ctx.status(status).json(body).contentType(PROBLEM_JSON);
    }
}
