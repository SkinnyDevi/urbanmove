package com.urbanmove.inventory.interfaces.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.urbanmove.inventory.application.GetVehicleUseCase;
import com.urbanmove.inventory.application.ListVehiclesUseCase;
import com.urbanmove.inventory.application.RetireCommand;
import com.urbanmove.inventory.application.RetireVehicleUseCase;
import com.urbanmove.inventory.application.port.AssetPage;
import com.urbanmove.inventory.application.port.AssetSearch;
import com.urbanmove.inventory.domain.AdminStatus;
import com.urbanmove.inventory.domain.VehicleAsset;
import com.urbanmove.inventory.domain.VehicleType;
import com.urbanmove.inventory.interfaces.http.ApiProblem.FieldError;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Rutas REST de inventory.yaml: listado, detalle y baja definitiva. */
public final class VehicleHandlers {
    private final ListVehiclesUseCase list;
    private final GetVehicleUseCase get;
    private final RetireVehicleUseCase retire;
    private final RequestParser parser;

    public VehicleHandlers(ListVehiclesUseCase list, GetVehicleUseCase get, RetireVehicleUseCase retire,
                           ObjectMapper json) {
        this.list = Objects.requireNonNull(list);
        this.get = Objects.requireNonNull(get);
        this.retire = Objects.requireNonNull(retire);
        this.parser = new RequestParser(json);
    }

    public void listVehicles(Context ctx) {
        List<FieldError> errors = new ArrayList<>();
        VehicleType type = query("vehicleType", ctx.queryParam("vehicleType"), VehicleType::valueOf, errors);
        AdminStatus status = query("adminStatus", ctx.queryParam("adminStatus"), AdminStatus::valueOf, errors);
        Integer limit = query("limit", ctx.queryParam("limit"), VehicleHandlers::limit, errors);
        if (!errors.isEmpty()) {
            throw ApiProblem.validation(errors);
        }
        AssetPage page = list.execute(new AssetSearch(type, status,
                limit == null ? AssetSearch.DEFAULT_LIMIT : limit, ctx.queryParam("cursor")));
        ctx.json(new VehicleResponse.Page(page.items().stream().map(VehicleResponse::from).toList(),
                page.nextCursor()));
    }

    public void getVehicle(Context ctx) {
        ctx.json(VehicleResponse.from(get.execute(RequestContext.vehicleId(ctx))));
    }

    public void retireVehicle(Context ctx) {
        String key = RequestParser.idempotencyKey(ctx);
        RequestParser.Body body = parser.body(ctx, Set.of("reason"));
        String reason = body.required("reason", RequestParser.text(1, VehicleAsset.MAX_REASON));
        body.validate();
        ctx.json(VehicleResponse.from(retire.execute(new RetireCommand(key, RequestContext.correlationId(ctx),
                RequestContext.vehicleId(ctx), reason)).vehicle()));
    }

    private static <T> T query(String name, String value, Function<String, T> parser, List<FieldError> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return parser.apply(value);
        } catch (IllegalArgumentException e) {
            errors.add(new FieldError(name, "is not valid: " + value));
            return null;
        }
    }

    private static Integer limit(String value) {
        int limit = Integer.parseInt(value);
        if (limit < 1 || limit > AssetSearch.MAX_LIMIT) {
            throw new IllegalArgumentException("must be between 1 and " + AssetSearch.MAX_LIMIT);
        }
        return limit;
    }
}
