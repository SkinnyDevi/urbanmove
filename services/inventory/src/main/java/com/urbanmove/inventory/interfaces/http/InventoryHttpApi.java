package com.urbanmove.inventory.interfaces.http;

import static com.urbanmove.inventory.interfaces.http.Role.ADMIN;
import static com.urbanmove.inventory.interfaces.http.Role.MAINTENANCE;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.Javalin;
import io.javalin.json.JavalinJackson;

/** Servidor HTTP del servicio: rutas de inventory.yaml con sus roles, correlationId y errores RFC 7807. */
public final class InventoryHttpApi {

    private InventoryHttpApi() {
    }

    public static Javalin create(ObjectMapper json, JwtVerifier jwt, HealthCheckHandler health,
                                 VehicleHandlers vehicles) {
        return Javalin.create(config -> {
            config.jsonMapper(new JavalinJackson(json, false));
            config.http.prefer405over404 = true;
            config.startup.showJavalinBanner = false;

            config.routes.before(RequestContext::assignCorrelationId);
            config.routes.beforeMatched(ctx -> RequestContext.authorize(ctx, jwt));
            config.routes.after(RequestContext::clear);

            config.routes.get("/health", health);
            config.routes.get("/vehicles", vehicles::listVehicles, MAINTENANCE, ADMIN);
            config.routes.get("/vehicles/{vehicleId}", vehicles::getVehicle, MAINTENANCE, ADMIN);
            config.routes.post("/vehicles/{vehicleId}/retire", vehicles::retireVehicle, ADMIN);

            ProblemHandlers.register(config.routes);
        });
    }
}
