package com.urbanmove.relocation.interfaces.http;

import com.urbanmove.relocation.domain.Identifiers.RelocationOrderId;
import com.urbanmove.relocation.domain.Identifiers.TechnicianId;
import com.urbanmove.relocation.infrastructure.logging.JsonLogEncoder;
import io.javalin.http.Context;
import io.javalin.security.RouteRole;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;

/** Lo común a cada petición HTTP: correlationId, usuario del JWT e id de la orden. */
final class RequestContext {
    static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String CORRELATION = "correlationId";
    private static final String USER = "user";

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
        ctx.attribute(USER, user);
    }

    static TechnicianId technician(Context ctx) {
        JwtVerifier.AuthenticatedUser user = ctx.attribute(USER);
        try {
            return new TechnicianId(user.userId());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiProblem.forbidden("The JWT subject is not a valid technician id");
        }
    }

    static RelocationOrderId orderId(Context ctx) {
        try {
            return new RelocationOrderId(ctx.pathParam("orderId"));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.validation("orderId", e.getMessage());
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
