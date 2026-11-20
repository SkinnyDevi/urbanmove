package com.urbanmove.zones.interfaces.rest;

import com.urbanmove.zones.application.GetZoneUseCase;
import com.urbanmove.zones.application.ListZonesUseCase;
import com.urbanmove.zones.domain.ZoneId;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Adaptador REST de docs/contracts/openapi/zones.yaml.
 *
 * <p>Solo traduce HTTP ↔ casos de uso (AGENTS.md 4.2, SRP): ni SQL ni reglas de negocio.
 */
@RestController
public class ZoneController {

    private final ListZonesUseCase listZones;
    private final GetZoneUseCase getZone;

    public ZoneController(ListZonesUseCase listZones, GetZoneUseCase getZone) {
        this.listZones = listZones;
        this.getZone = getZone;
    }

    /** operationId: listZones */
    @GetMapping("/zones")
    public ZoneListResponse listZones() {
        List<ZoneResponse> items = listZones.execute().stream().map(ZoneResponse::from).toList();
        return new ZoneListResponse(items);
    }

    /** operationId: getZone */
    @GetMapping("/zones/{zoneId}")
    public ZoneResponse getZone(@PathVariable String zoneId) {
        return ZoneResponse.from(getZone.execute(new ZoneId(zoneId)));
    }
}
