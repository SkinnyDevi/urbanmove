package com.urbanmove.synthgen;

/**
 * Foto de una zona al final de una hora: la "verdad" con la que comprobar el data mart de demanda.
 */
public record ZoneHour(String hourStart, String zoneId, int availableAtEnd, int demandSignals, int unmetRequests,
                       boolean hotspot) {
}
