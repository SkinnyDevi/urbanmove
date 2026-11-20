package com.urbanmove.zones.interfaces.rest;

import java.util.List;

/** DTO de salida de GET /zones. */
public record ZoneListResponse(List<ZoneResponse> items) {
}
