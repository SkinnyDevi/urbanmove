package com.urbanmove.relocation.interfaces.http;

import com.urbanmove.relocation.application.GetRelocationOrderUseCase;
import com.urbanmove.relocation.application.ListRelocationOrdersUseCase;
import com.urbanmove.relocation.application.port.OrderPage;
import com.urbanmove.relocation.application.port.OrderSearch;
import com.urbanmove.relocation.domain.Identifiers.TechnicianId;
import com.urbanmove.relocation.domain.Identifiers.ZoneId;
import com.urbanmove.relocation.domain.RelocationOrderStatus;
import com.urbanmove.relocation.interfaces.http.ApiProblem.FieldError;
import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Consultas REST de relocation.yaml: listado con filtros y detalle de una orden. */
public final class QueryHandlers {
    private final ListRelocationOrdersUseCase list;
    private final GetRelocationOrderUseCase get;

    public QueryHandlers(ListRelocationOrdersUseCase list, GetRelocationOrderUseCase get) {
        this.list = Objects.requireNonNull(list);
        this.get = Objects.requireNonNull(get);
    }

    public void listOrders(Context ctx) {
        List<FieldError> errors = new ArrayList<>();
        Set<RelocationOrderStatus> statuses = EnumSet.noneOf(RelocationOrderStatus.class);
        String statusParam = ctx.queryParam("status");
        if (statusParam != null && !statusParam.isBlank()) {
            for (String value : statusParam.split(",")) {
                RelocationOrderStatus status = query("status", value.trim(), RelocationOrderStatus::valueOf, errors);
                if (status != null) {
                    statuses.add(status);
                }
            }
        }
        ZoneId source = query("sourceZoneId", ctx.queryParam("sourceZoneId"), ZoneId::new, errors);
        ZoneId target = query("targetZoneId", ctx.queryParam("targetZoneId"), ZoneId::new, errors);
        TechnicianId assignee = query("assigneeId", ctx.queryParam("assigneeId"), TechnicianId::new, errors);
        Integer limit = query("limit", ctx.queryParam("limit"), QueryHandlers::limit, errors);
        if (!errors.isEmpty()) {
            throw ApiProblem.validation(errors);
        }
        OrderPage page = list.execute(new OrderSearch(statuses, source, target, assignee,
                limit == null ? OrderSearch.DEFAULT_LIMIT : limit, ctx.queryParam("cursor")));
        ctx.json(new OrderResponse.Page(page.items().stream().map(OrderResponse::from).toList(), page.nextCursor()));
    }

    public void getOrder(Context ctx) {
        ctx.json(OrderResponse.from(get.execute(RequestContext.orderId(ctx))));
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
        if (limit < 1 || limit > OrderSearch.MAX_LIMIT) {
            throw new IllegalArgumentException("must be between 1 and " + OrderSearch.MAX_LIMIT);
        }
        return limit;
    }
}
