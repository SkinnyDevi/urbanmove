package com.urbanmove.maintenance.interfaces.http;

import com.urbanmove.maintenance.application.CommandResult;
import com.urbanmove.maintenance.application.GetMaintenanceOrderUseCase;
import com.urbanmove.maintenance.application.ListMaintenanceOrdersUseCase;
import com.urbanmove.maintenance.application.OpenMaintenanceOrderUseCase;
import com.urbanmove.maintenance.application.OpenOrderCommand;
import com.urbanmove.maintenance.application.OrderActionCommand;
import com.urbanmove.maintenance.application.PerformOrderActionUseCase;
import com.urbanmove.maintenance.application.port.OrderPage;
import com.urbanmove.maintenance.application.port.OrderSearch;
import com.urbanmove.maintenance.domain.Identifiers.MaintenanceOrderId;
import com.urbanmove.maintenance.domain.Identifiers.TechnicianId;
import com.urbanmove.maintenance.domain.Identifiers.VehicleId;
import com.urbanmove.maintenance.domain.Identifiers.ZoneId;
import com.urbanmove.maintenance.domain.MaintenanceAction;
import com.urbanmove.maintenance.domain.MaintenanceOrder;
import com.urbanmove.maintenance.domain.MaintenanceOrderStatus;
import com.urbanmove.maintenance.domain.MaintenanceReason;
import com.urbanmove.maintenance.domain.VehicleType;
import com.urbanmove.maintenance.interfaces.http.ApiProblem.FieldError;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Adaptador REST de docs/contracts/openapi/maintenance.yaml.
 *
 * <p>Solo traduce HTTP ↔ casos de uso (AGENTS.md 4.2, SRP): lee y valida la petición, llama al
 * caso de uso y escribe la respuesta. Las reglas están en el dominio y en los casos de uso.
 */
public final class OrderHandlers {

    private final OpenMaintenanceOrderUseCase open;
    private final PerformOrderActionUseCase act;
    private final GetMaintenanceOrderUseCase get;
    private final ListMaintenanceOrdersUseCase list;
    private final RequestParser parser;

    public OrderHandlers(OpenMaintenanceOrderUseCase open, PerformOrderActionUseCase act,
                         GetMaintenanceOrderUseCase get, ListMaintenanceOrdersUseCase list,
                         com.fasterxml.jackson.databind.ObjectMapper json) {
        this.open = Objects.requireNonNull(open);
        this.act = Objects.requireNonNull(act);
        this.get = Objects.requireNonNull(get);
        this.list = Objects.requireNonNull(list);
        this.parser = new RequestParser(json);
    }

    /** operationId: openMaintenanceOrder */
    public void openOrder(Context ctx) {
        String key = RequestParser.idempotencyKey(ctx);
        RequestParser.Body body = parser.body(ctx,
                Set.of("vehicleId", "vehicleType", "reason", "zoneId", "notes", "triggeredByEventId"));
        VehicleId vehicleId = body.required("vehicleId", VehicleId::new);
        VehicleType vehicleType = body.required("vehicleType", RequestParser.enumValue(VehicleType.class));
        MaintenanceReason reason = body.required("reason", RequestParser.enumValue(MaintenanceReason.class));
        ZoneId zoneId = body.required("zoneId", ZoneId::new);
        String notes = body.optional("notes", RequestParser.text(0, MaintenanceOrder.MAX_NOTES));
        UUID triggeredBy = body.optional("triggeredByEventId", UUID::fromString);
        body.validate();

        CommandResult result = open.execute(new OpenOrderCommand(key, MaintenanceHttpApi.correlationId(ctx),
                vehicleId, vehicleType, reason, zoneId, notes, triggeredBy));
        if (!result.replayed()) {
            ctx.header("Location", "/maintenance-orders/" + result.order().orderId().value());
        }
        ctx.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).json(OrderResponse.from(result.order()));
    }

    /** operationId: listMaintenanceOrders */
    public void listOrders(Context ctx) {
        List<FieldError> errors = new ArrayList<>();
        Set<MaintenanceOrderStatus> statuses = EnumSet.noneOf(MaintenanceOrderStatus.class);
        String statusParam = ctx.queryParam("status");
        if (statusParam != null && !statusParam.isBlank()) {
            for (String value : statusParam.split(",")) {
                MaintenanceOrderStatus status = query("status", value.trim(),
                        RequestParser.enumValue(MaintenanceOrderStatus.class), errors);
                if (status != null) {
                    statuses.add(status);
                }
            }
        }
        ZoneId zone = query("zoneId", ctx.queryParam("zoneId"), ZoneId::new, errors);
        VehicleId vehicle = query("vehicleId", ctx.queryParam("vehicleId"), VehicleId::new, errors);
        TechnicianId technician = query("technicianId", ctx.queryParam("technicianId"), TechnicianId::new, errors);
        Integer limit = query("limit", ctx.queryParam("limit"), OrderHandlers::limit, errors);
        if (!errors.isEmpty()) {
            throw ApiProblem.validation(errors);
        }
        OrderPage page = list.execute(new OrderSearch(statuses, zone, vehicle, technician,
                limit == null ? OrderSearch.DEFAULT_LIMIT : limit, ctx.queryParam("cursor")));
        ctx.json(new OrderResponse.Page(page.items().stream().map(OrderResponse::from).toList(), page.nextCursor()));
    }

    /** operationId: getMaintenanceOrder */
    public void getOrder(Context ctx) {
        ctx.json(OrderResponse.from(get.execute(orderId(ctx))));
    }

    /** operationId: withdrawVehicle. El técnico es el usuario del JWT. */
    public void withdraw(Context ctx) {
        TechnicianId technician;
        try {
            technician = new TechnicianId(MaintenanceHttpApi.user(ctx).userId());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiProblem.forbidden("The JWT subject is not a valid technician id");
        }
        respond(ctx, act.execute(new OrderActionCommand(RequestParser.idempotencyKey(ctx),
                MaintenanceHttpApi.correlationId(ctx), orderId(ctx), MaintenanceAction.WITHDRAW_FOR_MAINTENANCE,
                technician, null, null)));
    }

    /** operationId: startCharging */
    public void startCharging(Context ctx) {
        simpleAction(ctx, MaintenanceAction.START_CHARGING);
    }

    /** operationId: startRepair */
    public void startRepair(Context ctx) {
        simpleAction(ctx, MaintenanceAction.START_REPAIR);
    }

    /** operationId: markReadyForService */
    public void markReady(Context ctx) {
        simpleAction(ctx, MaintenanceAction.MARK_READY_FOR_SERVICE);
    }

    /** operationId: returnToService */
    public void returnToService(Context ctx) {
        String key = RequestParser.idempotencyKey(ctx);
        RequestParser.Body body = parser.body(ctx, Set.of("zoneId"));
        ZoneId zone = body.required("zoneId", ZoneId::new);
        body.validate();
        respond(ctx, act.execute(new OrderActionCommand(key, MaintenanceHttpApi.correlationId(ctx), orderId(ctx),
                MaintenanceAction.RETURN_TO_SERVICE, null, zone, null)));
    }

    /** operationId: cancelOrder */
    public void cancel(Context ctx) {
        String key = RequestParser.idempotencyKey(ctx);
        RequestParser.Body body = parser.body(ctx, Set.of("reason"));
        String reason = body.required("reason", RequestParser.text(1, MaintenanceOrder.MAX_REASON));
        body.validate();
        respond(ctx, act.execute(new OrderActionCommand(key, MaintenanceHttpApi.correlationId(ctx), orderId(ctx),
                MaintenanceAction.CANCEL, null, null, reason)));
    }

    private void simpleAction(Context ctx, MaintenanceAction action) {
        respond(ctx, act.execute(OrderActionCommand.simple(RequestParser.idempotencyKey(ctx),
                MaintenanceHttpApi.correlationId(ctx), orderId(ctx), action)));
    }

    private static void respond(Context ctx, CommandResult result) {
        ctx.json(OrderResponse.from(result.order()));
    }

    private static MaintenanceOrderId orderId(Context ctx) {
        try {
            return new MaintenanceOrderId(ctx.pathParam("orderId"));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.validation("orderId", e.getMessage());
        }
    }

    private static <T> T query(String name, String value, Function<String, T> parser, List<FieldError> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parser.apply(value);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError(name, e.getMessage()));
            return null;
        }
    }

    private static Integer limit(String value) {
        int limit;
        try {
            limit = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("must be an integer");
        }
        if (limit < 1 || limit > OrderSearch.MAX_LIMIT) {
            throw new IllegalArgumentException("must be between 1 and " + OrderSearch.MAX_LIMIT);
        }
        return limit;
    }
}
