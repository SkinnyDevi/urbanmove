package com.urbanmove.synthgen;

/**
 * Foto de una zona al final de una hora: la "verdad" con la que comprobar el data mart de demanda.
 *
 * @param availableAtEnd vehículos en estado AVAILABLE al acabar la hora (lo que vería el servicio de
 *                       disponibilidad a partir de los eventos)
 * @param demandSignals  reservas + viajes iniciados sin reserva durante esa hora
 * @param unmetRequests  personas que querían un vehículo y no encontraron ninguno
 * @param hotspot        true si se cumple la regla de déficit del enunciado
 */
public record ZoneHour(String hourStart, String zoneId, int availableAtEnd, int demandSignals, int unmetRequests,
                       boolean hotspot) {
}
