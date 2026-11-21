package com.urbanmove.maintenance.interfaces.http;

import static com.urbanmove.maintenance.interfaces.http.Role.ADMIN;
import static com.urbanmove.maintenance.interfaces.http.Role.MAINTENANCE;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.maintenance.application.ActiveOrderExistsException;
import com.urbanmove.maintenance.application.ConcurrentUpdateException;
import com.urbanmove.maintenance.application.IdempotencyKeyReusedException;
import com.urbanmove.maintenance.application.OrderNotFoundException;
import com.urbanmove.maintenance.domain.InvalidStateTransitionError;
import com.urbanmove.maintenance.infrastructure.logging.JsonLogEncoder;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpResponseException;
import io.javalin.http.HttpStatus;
import io.javalin.json.JavalinJackson;
import io.javalin.security.RouteRole;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * Servidor HTTP del servicio (adaptador primario). Lo transversal, en este orden por petición:
 *
 * <ol>
 *   <li>correlationId: se lee o se genera, va al MDC (logs), a la respuesta y a los eventos (AGENTS.md 6.1);</li>
 *   <li>seguridad: si la ruta tiene roles, se exige un JWT válido con uno de ellos (AGENTS.md 3.1);</li>
 *   <li>el manejador de la ruta;</li>
 *   <li>errores: cualquier excepción sale como RFC 7807 con correlationId (AGENTS.md 3.1).</li>
 * </ol>
 */
public final class MaintenanceHttpApi {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String PROBLEM_JSON = "application/problem+json";

    private static final Logger log = LoggerFactory.getLogger(MaintenanceHttpApi.class);
    private static final String USER = "user";
    private static final String CORRELATION = "correlationId";

    private MaintenanceHttpApi() {
    }

    public static Javalin create(ObjectMapper json, JwtVerifier jwt, HealthCheckHandler health, OrderHandlers orders) {
        return Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(json, false));
            config.http.prefer405over404 = true;
            config.startup.showJavalinBanner = false;

            config.routes.before(MaintenanceHttpApi::assignCorrelationId);
            config.routes.beforeMatched(ctx -> authorize(ctx, jwt));
            config.routes.after(ctx -> MDC.remove(JsonLogEncoder.CORRELATION_ID));

            // Rutas sin roles = públicas. Con roles = JWT obligatorio con uno de ellos (contrato).
            config.routes.get("/health", health);
            config.routes.post("/maintenance-orders", orders::openOrder, MAINTENANCE, ADMIN);
            config.routes.get("/maintenance-orders", orders::listOrders, MAINTENANCE, ADMIN);
            config.routes.get("/maintenance-orders/{orderId}", orders::getOrder, MAINTENANCE, ADMIN);
            config.routes.post("/maintenance-orders/{orderId}/withdraw", orders::withdraw, MAINTENANCE);
            config.routes.post("/maintenance-orders/{orderId}/start-charging", orders::startCharging, MAINTENANCE);
            config.routes.post("/maintenance-orders/{orderId}/start-repair", orders::startRepair, MAINTENANCE);
            config.routes.post("/maintenance-orders/{orderId}/mark-ready", orders::markReady, MAINTENANCE);
            config.routes.post("/maintenance-orders/{orderId}/return-to-service", orders::returnToService, MAINTENANCE);
            config.routes.post("/maintenance-orders/{orderId}/cancel", orders::cancel, MAINTENANCE, ADMIN);

            config.routes.exception(ApiProblem.class, (e, ctx) ->
                    problem(ctx, e.status(), e.type(), e.getMessage(), e.extensions()));
            config.routes.exception(OrderNotFoundException.class, (e, ctx) ->
                    problem(ctx, HttpStatus.NOT_FOUND, "/problems/not-found", e.getMessage(), Map.of()));
            config.routes.exception(InvalidStateTransitionError.class, (e, ctx) -> {
                log.atInfo().addKeyValue("currentState", e.from().name()).addKeyValue("action", e.action().name())
                        .log("Invalid state transition rejected");
                problem(ctx, HttpStatus.CONFLICT, "/problems/invalid-state-transition", e.getMessage(),
                        Map.of("currentState", e.from().name(), "action", e.action().name()));
            });
            config.routes.exception(ActiveOrderExistsException.class, (e, ctx) -> {
                Map<String, Object> extra = new HashMap<>();
                if (e.existingOrderId() != null) {
                    extra.put("existingOrderId", e.existingOrderId().value());
                }
                problem(ctx, HttpStatus.CONFLICT, "/problems/active-order-exists", e.getMessage(), extra);
            });
            config.routes.exception(ConcurrentUpdateException.class, (e, ctx) ->
                    problem(ctx, HttpStatus.CONFLICT, "/problems/concurrent-update", e.getMessage(), Map.of()));
            config.routes.exception(IdempotencyKeyReusedException.class, (e, ctx) ->
                    problem(ctx, HttpStatus.CONFLICT, "/problems/idempotency-key-reused", e.getMessage(), Map.of()));
            // Identificadores con formato incorrecto, cursor no válido...
            config.routes.exception(IllegalArgumentException.class, (e, ctx) ->
                    problem(ctx, HttpStatus.BAD_REQUEST, "/problems/validation", e.getMessage(), Map.of()));
            // Rutas que no existen, métodos no permitidos... (excepciones propias de Javalin)
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

    /** correlationId de la petición en curso (lo pone el primer filtro). */
    static String correlationId(Context ctx) {
        String id = ctx.attribute(CORRELATION);
        return id != null ? id : UUID.randomUUID().toString();
    }

    /** Usuario autenticado de la petición (solo en rutas con roles). */
    static JwtVerifier.AuthenticatedUser user(Context ctx) {
        return ctx.attribute(USER);
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
                // Cabecera mal formada: se genera una nueva en lugar de propagar basura a logs y eventos.
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
        ctx.attribute(USER, user);
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
        body.put("correlationId", correlationId(ctx));
        body.putAll(extra);
        ctx.status(status).json(body).contentType(PROBLEM_JSON);
    }
}
