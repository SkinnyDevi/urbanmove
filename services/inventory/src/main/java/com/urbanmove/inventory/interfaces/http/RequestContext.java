package com.urbanmove.inventory.interfaces.http;

import com.urbanmove.inventory.domain.VehicleId;
import com.urbanmove.inventory.infrastructure.logging.JsonLogEncoder;
import io.javalin.http.Context;
import io.javalin.security.RouteRole;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;

/** Lo común a cada petición HTTP: correlationId, usuario del JWT e id del vehículo. */
final class RequestContext {
    static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String CORRELATION = "correlationId";

    private RequestContext() {
    }

    static void assignCorrelationId(Context ctx) {
        String correlationId = validOrNew(ctx.header(CORRELATION_HEADER));
        MDC.put(JsonLogEncoder.CORRELATION_ID, correlationId);
        ctx.attribute(CORRELATION, correlationId);
        ctx.header(CORRELATION_HEADER, correlationId);
    }

    static void clear(Context ctx) {
        MDC.remove(JsonLogEncoder.CORRELATION_ID);
    }

    static String correlationId(Context ctx) {
        String id = ctx.attribute(CORRELATION);
        return id != null ? id : UUID.randomUUID().toString();
    }

    static void authorize(Context ctx, JwtVerifier jwt) {
        Set<RouteRole> allowed = ctx.routeRoles();
        if (allowed.isEmpty()) {
            return;
        }
        JwtVerifier.AuthenticatedUser user = jwt.verify(ctx.header("Authorization"));
        if (Collections.disjoint(allowed, user.roles())) {
            throw ApiProblem.forbidden();
        }
    }

    static VehicleId vehicleId(Context ctx) {
        try {
            return new VehicleId(ctx.pathParam("vehicleId"));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.validation("vehicleId", e.getMessage());
        }
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
}
