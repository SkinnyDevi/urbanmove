package com.urbanmove.availability.interfaces.http;

import com.urbanmove.availability.application.GetVehicleUseCase;
import com.urbanmove.availability.application.ListVehiclesUseCase;
import com.urbanmove.availability.application.ZoneSummaryUseCase;
import com.urbanmove.availability.application.port.VehiclePage;
import com.urbanmove.availability.application.port.VehicleSearch;
import com.urbanmove.availability.domain.VehicleId;
import com.urbanmove.availability.domain.VehicleStatus;
import com.urbanmove.availability.domain.VehicleType;
import com.urbanmove.availability.domain.ZoneId;
import com.urbanmove.availability.interfaces.http.ApiProblem.FieldError;
import io.javalin.http.Context;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Adaptador REST de docs/contracts/openapi/availability.yaml. */
public final class VehicleHandlers {
    private final ListVehiclesUseCase list;
    private final GetVehicleUseCase get;
    private final ZoneSummaryUseCase summary;
    private final Clock clock;

    public VehicleHandlers(ListVehiclesUseCase list, GetVehicleUseCase get, ZoneSummaryUseCase summary, Clock clock) {
        this.list = Objects.requireNonNull(list);
        this.get = Objects.requireNonNull(get);
        this.summary = Objects.requireNonNull(summary);
        this.clock = Objects.requireNonNull(clock);
    }

    public void listVehicles(Context ctx) {
        List<FieldError> errors = new ArrayList<>();
        VehicleStatus status = query("status", ctx.queryParam("status"), VehicleStatus::valueOf, errors);
        ZoneId zone = query("zoneId", ctx.queryParam("zoneId"), ZoneId::new, errors);
        VehicleType type = query("vehicleType", ctx.queryParam("vehicleType"), VehicleType::valueOf, errors);
        Integer limit = query("limit", ctx.queryParam("limit"), VehicleHandlers::limit, errors);
        if (!errors.isEmpty()) {
            throw ApiProblem.validation(errors);
        }
        if (status == null || !AvailabilityHttpApi.user(ctx).isStaff()) {
            status = VehicleStatus.AVAILABLE;
        }
        VehiclePage page = list.execute(new VehicleSearch(status, zone, type,
                limit == null ? VehicleSearch.DEFAULT_LIMIT : limit, ctx.queryParam("cursor")));
        ctx.json(new VehicleResponses.Page(page.items().stream().map(VehicleResponses.Vehicle::from).toList(),
                page.nextCursor()));
    }

    public void getVehicle(Context ctx) {
        VehicleId id;
        try {
            id = new VehicleId(ctx.pathParam("vehicleId"));
        } catch (IllegalArgumentException e) {
            throw ApiProblem.validation("vehicleId", e.getMessage());
        }
        ctx.json(VehicleResponses.Vehicle.from(get.execute(id)));
    }

    public void zoneSummary(Context ctx) {
        List<FieldError> errors = new ArrayList<>();
        ZoneId zone = query("zoneId", ctx.queryParam("zoneId"), ZoneId::new, errors);
        VehicleType type = query("vehicleType", ctx.queryParam("vehicleType"), VehicleType::valueOf, errors);
        if (!errors.isEmpty()) {
            throw ApiProblem.validation(errors);
        }
        ctx.json(new VehicleResponses.Summary(clock.instant().toString(),
                summary.execute(zone, type).stream().map(VehicleResponses.Zone::from).toList()));
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
        if (limit < 1 || limit > VehicleSearch.MAX_LIMIT) {
            throw new IllegalArgumentException("must be between 1 and " + VehicleSearch.MAX_LIMIT);
        }
        return limit;
    }
}
