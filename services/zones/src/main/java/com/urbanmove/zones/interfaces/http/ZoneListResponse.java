package com.urbanmove.zones.interfaces.http;

import java.util.List;

/** DTO de salida de GET /zones. */
public record ZoneListResponse(List<ZoneResponse> items) {
}
