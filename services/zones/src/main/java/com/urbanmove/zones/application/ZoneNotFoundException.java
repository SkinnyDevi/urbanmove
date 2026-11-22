package com.urbanmove.zones.application;

import com.urbanmove.zones.domain.ZoneId;

/** La zona pedida no existe. */
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
