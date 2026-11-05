package com.urbanmove.synthgen;

/** Clave "esta hora (contada desde el inicio) en esta zona" para los contadores de demanda. */
public record HourZone(long hourIndex, String zoneId) {
}
