package com.urbanmove.relocation.interfaces.http;

import com.urbanmove.relocation.application.PerformRelocationActionUseCase;
import com.urbanmove.relocation.application.RelocationCommand;
import com.urbanmove.relocation.domain.Identifiers.VehicleId;
import com.urbanmove.relocation.domain.RelocationAction;
import com.urbanmove.relocation.domain.RelocationOrder;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.http.Context;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Acciones REST de relocation.yaml: asignar, empezar, completar y cancelar una orden. */
public final class ActionHandlers {
    private final PerformRelocationActionUseCase act;
    private final RequestParser parser;

    public ActionHandlers(PerformRelocationActionUseCase act, ObjectMapper json) {
        this.act = Objects.requireNonNull(act);
        this.parser = new RequestParser(json);
    }

    public void assign(Context ctx) {
        byTechnician(ctx, RelocationAction.ASSIGN);
    }

    public void start(Context ctx) {
        byTechnician(ctx, RelocationAction.START);
    }

    public void complete(Context ctx) {
        String key = RequestParser.idempotencyKey(ctx);
        RequestParser.Body body = parser.body(ctx, Set.of("vehicleIds"));
        List<VehicleId> vehicles = body.requiredList("vehicleIds", VehicleId::new);
        body.validate();
        respond(ctx, new RelocationCommand(key, RequestContext.correlationId(ctx), RequestContext.orderId(ctx),
                RelocationAction.COMPLETE, RequestContext.technician(ctx), vehicles, null));
    }

    public void cancel(Context ctx) {
        String key = RequestParser.idempotencyKey(ctx);
        RequestParser.Body body = parser.body(ctx, Set.of("reason"));
        String reason = body.required("reason", RequestParser.text(1, RelocationOrder.MAX_REASON));
        body.validate();
        respond(ctx, new RelocationCommand(key, RequestContext.correlationId(ctx), RequestContext.orderId(ctx),
                RelocationAction.CANCEL, null, List.of(), reason));
    }

    private void byTechnician(Context ctx, RelocationAction action) {
        respond(ctx, RelocationCommand.byTechnician(RequestParser.idempotencyKey(ctx),
                RequestContext.correlationId(ctx), RequestContext.orderId(ctx), action, RequestContext.technician(ctx)));
    }

    private void respond(Context ctx, RelocationCommand command) {
        ctx.json(OrderResponse.from(act.execute(command).order()));
    }
}
