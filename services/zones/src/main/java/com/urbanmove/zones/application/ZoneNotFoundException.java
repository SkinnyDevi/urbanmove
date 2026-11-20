package com.urbanmove.zones.application;

import com.urbanmove.zones.domain.ZoneId;

/** La zona pedida no existe. La capa interfaces la convierte en un 404. */
public final class ZoneNotFoundException extends RuntimeException {

    private final ZoneId zoneId;

    public ZoneNotFoundException(ZoneId zoneId) {
        super("Zone not found: " + zoneId);
        this.zoneId = zoneId;
    }

    public ZoneId zoneId() {
        return zoneId;
    }
}
