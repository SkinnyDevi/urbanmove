package com.urbanmove.zones.interfaces.http;

import com.urbanmove.zones.application.GetZoneUseCase;
import com.urbanmove.zones.application.ListZonesUseCase;
import com.urbanmove.zones.domain.ZoneId;
import io.javalin.http.Context;
import java.util.Objects;

/** Adaptador REST de docs/contracts/openapi/zones.yaml. */
public final class ZoneHandlers {
    private final ListZonesUseCase listZones;
    private final GetZoneUseCase getZone;

    public ZoneHandlers(ListZonesUseCase listZones, GetZoneUseCase getZone) {
        this.listZones = Objects.requireNonNull(listZones);
        this.getZone = Objects.requireNonNull(getZone);
    }

    public void listZones(Context ctx) {
        ctx.json(new ZoneListResponse(listZones.execute().stream().map(ZoneResponse::from).toList()));
    }

    public void getZone(Context ctx) {
        ZoneId id = new ZoneId(ctx.pathParam("zoneId"));
        ctx.json(ZoneResponse.from(getZone.execute(id)));
    }
}
