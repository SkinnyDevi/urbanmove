package com.urbanmove.zones.interfaces.http;

import com.urbanmove.zones.application.GetZoneUseCase;
import com.urbanmove.zones.application.ListZonesUseCase;
import com.urbanmove.zones.domain.ZoneId;
import io.javalin.http.Context;
import java.util.Objects;

/**
 * Adaptador REST de docs/contracts/openapi/zones.yaml.
 *
 * <p>Solo traduce HTTP ↔ casos de uso (AGENTS.md 4.2, SRP): ni SQL ni reglas de negocio.
 */
public final class ZoneHandlers {

    private final ListZonesUseCase listZones;
    private final GetZoneUseCase getZone;

    public ZoneHandlers(ListZonesUseCase listZones, GetZoneUseCase getZone) {
        this.listZones = Objects.requireNonNull(listZones);
        this.getZone = Objects.requireNonNull(getZone);
    }

    /** operationId: listZones */
    public void listZones(Context ctx) {
        ctx.json(new ZoneListResponse(listZones.execute().stream().map(ZoneResponse::from).toList()));
    }

    /** operationId: getZone */
    public void getZone(Context ctx) {
        ZoneId id = new ZoneId(ctx.pathParam("zoneId"));
        ctx.json(ZoneResponse.from(getZone.execute(id)));
    }
}
