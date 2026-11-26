package com.urbanmove.relocation.interfaces.http;

import static com.urbanmove.relocation.interfaces.http.Role.ADMIN;
import static com.urbanmove.relocation.interfaces.http.Role.MAINTENANCE;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;

/** Servidor HTTP del servicio: rutas de relocation.yaml con sus roles, correlationId y errores RFC 7807. */
public final class RelocationHttpApi {

    private RelocationHttpApi() {
    }

    public static Javalin create(ObjectMapper json, JwtVerifier jwt, HealthCheckHandler health,
                                 QueryHandlers queries, ActionHandlers actions) {
        return Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(json, false));
            config.http.prefer405over404 = true;
            config.startup.showJavalinBanner = false;

            config.routes.before(RequestContext::assignCorrelationId);
            config.routes.beforeMatched(ctx -> RequestContext.authorize(ctx, jwt));
            config.routes.after(RequestContext::clear);

            config.routes.get("/health", health);
            config.routes.get("/relocation-orders", queries::listOrders, MAINTENANCE, ADMIN);
            config.routes.get("/relocation-orders/{orderId}", queries::getOrder, MAINTENANCE, ADMIN);
            config.routes.post("/relocation-orders/{orderId}/assign", actions::assign, MAINTENANCE);
            config.routes.post("/relocation-orders/{orderId}/start", actions::start, MAINTENANCE);
            config.routes.post("/relocation-orders/{orderId}/complete", actions::complete, MAINTENANCE);
            config.routes.post("/relocation-orders/{orderId}/cancel", actions::cancel, ADMIN);

            ProblemHandlers.register(config.routes);
        });
    }
}
