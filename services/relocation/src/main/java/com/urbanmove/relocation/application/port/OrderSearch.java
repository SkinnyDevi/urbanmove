package com.urbanmove.relocation.application.port;

import com.urbanmove.relocation.domain.Identifiers.TechnicianId;
import com.urbanmove.relocation.domain.Identifiers.ZoneId;
import com.urbanmove.relocation.domain.RelocationOrderStatus;
import java.util.EnumSet;
import java.util.Set;

/** Filtros de GET /relocation-orders. */
public record OrderSearch(Set<RelocationOrderStatus> statuses, ZoneId sourceZoneId, ZoneId targetZoneId,
                          TechnicianId assigneeId, int limit, String cursor) {
    public static final int MAX_LIMIT = 200;
    public static final int DEFAULT_LIMIT = 50;

    public OrderSearch {
        statuses = statuses == null || statuses.isEmpty()
                ? Set.copyOf(EnumSet.allOf(RelocationOrderStatus.class)) : Set.copyOf(statuses);
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }
}
