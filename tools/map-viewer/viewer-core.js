/*
 * UrbanMove · visor de mapa · LÓGICA (sin nada visual)
 *
 * Reconstruye el estado de la flota en cualquier momento "reproduciendo" los eventos del generador,
 * igual que haría el servicio de disponibilidad: empieza con la flota vacía y aplica los eventos uno a uno
 * en orden hasta el instante elegido.
 *
 * Está separado de app.js (la parte visual) para poder probarlo con tests en Node:
 *   node --test tools/map-viewer/test/viewer-core.test.js
 */
(function (root) {
  'use strict';

  const HOUR_MS = 3600 * 1000;
  const STATES = ['AVAILABLE', 'RESERVED', 'IN_USE', 'LOW_BATTERY', 'WITHDRAWN'];

  /** Lee un .jsonl (un evento JSON por línea) y añade _t: el timestamp en milisegundos. */
  function parseJsonl(text) {
    const events = [];
    for (const line of text.split(/\r?\n/)) {
      if (line.trim() === '') continue;
      const event = JSON.parse(line);
      event._t = Date.parse(event.timestamp);
      events.push(event);
    }
    events.sort((a, b) => a._t - b._t);
    return events;
  }

  /** Lee ground_truth_zone_hours.csv del generador. */
  function parseZoneHoursCsv(text) {
    const lines = text.split(/\r?\n/).filter((l) => l.trim() !== '');
    const header = lines.shift().split(',');
    return lines.map((line) => {
      const cells = line.split(',');
      const row = Object.fromEntries(header.map((name, i) => [name, cells[i]]));
      return {
        hourStart: Date.parse(row.hourStart),
        zoneId: row.zoneId,
        availableAtEnd: Number(row.availableAtEnd),
        demandSignals: Number(row.demandSignals),
        unmetRequests: Number(row.unmetRequests),
        hotspot: String(row.hotspot).toLowerCase() === 'true',
      };
    });
  }

  /** Para cada viaje: de dónde a dónde va y cuándo empieza y acaba (para dibujarlo en movimiento). */
  function indexRides(events) {
    const rides = new Map();
    for (const e of events) {
      if (e.eventType === 'RideStarted') {
        rides.set(e.payload.rideId, {
          vehicleId: e.payload.vehicleId, origin: e.payload.originZoneId, destination: null,
          start: e._t, end: null,
        });
      } else if (e.eventType === 'RideFinished' && rides.has(e.payload.rideId)) {
        const ride = rides.get(e.payload.rideId);
        ride.destination = e.payload.destinationZoneId;
        ride.end = e._t;
      }
    }
    return rides;
  }

  /** Desde el primer día de uso real (no el día de altas) hasta el último evento. */
  function timeRange(events) {
    const firstUse = events.find((e) => e.eventType === 'VehicleReserved' || e.eventType === 'RideStarted');
    const first = firstUse ? firstUse._t : events[0]._t;
    const start = new Date(first);
    start.setUTCHours(0, 0, 0, 0);
    return { start: start.getTime(), end: events[events.length - 1]._t };
  }

  /**
   * Reproduce los eventos hasta un instante. Avanzar es barato (solo aplica los eventos nuevos);
   * retroceder vuelve a empezar desde el principio, que con unos miles de eventos es instantáneo.
   */
  class Replay {
    constructor(events) {
      this.events = events;
      this.reset();
    }

    reset() {
      this.vehicles = new Map();   // vehicleId -> { id, type, state, zoneId, rideId }
      this.cursor = 0;
      this.time = -Infinity;
    }

    seek(t) {
      if (t < this.time) this.reset();
      while (this.cursor < this.events.length && this.events[this.cursor]._t <= t) {
        this.apply(this.events[this.cursor]);
        this.cursor++;
      }
      this.time = t;
      return this;
    }

    apply(e) {
      const p = e.payload;
      if (e.eventType === 'VehiclePurchased') {
        this.vehicles.set(p.vehicleId, {
          id: p.vehicleId, type: p.vehicleType, state: 'AVAILABLE', zoneId: p.initialZoneId, rideId: null,
        });
        return;
      }
      const v = p.vehicleId ? this.vehicles.get(p.vehicleId) : null;
      if (!v) return;
      switch (e.eventType) {
        case 'VehicleReserved': v.state = 'RESERVED'; break;
        case 'ReservationCancelled':
        case 'ReservationExpired': v.state = 'AVAILABLE'; break;
        case 'RideStarted': v.state = 'IN_USE'; v.rideId = p.rideId; break;
        case 'RideFinished': v.state = 'AVAILABLE'; v.zoneId = p.destinationZoneId; v.rideId = null; break;
        case 'VehicleBatteryLow': v.state = 'LOW_BATTERY'; break;
        case 'VehicleWithdrawnForMaintenance': v.state = 'WITHDRAWN'; break;
        case 'VehicleReturnedToService': v.state = 'AVAILABLE'; v.zoneId = p.zoneId; break;
        default: break;
      }
    }

    /** Totales por estado de toda la flota. */
    totals() {
      const totals = Object.fromEntries(STATES.map((s) => [s, 0]));
      for (const v of this.vehicles.values()) totals[v.state] += 1;
      return totals;
    }

    /** Por zona: cuántos hay en cada estado y cuántas bicis y patinetes disponibles. */
    zoneCounts(zoneIds) {
      const counts = {};
      for (const id of zoneIds) {
        counts[id] = Object.fromEntries(STATES.map((s) => [s, 0]));
        counts[id].availableBikes = 0;
        counts[id].availableScooters = 0;
      }
      for (const v of this.vehicles.values()) {
        if (v.state === 'IN_USE' || !counts[v.zoneId]) continue;   // los que van en ruta no están en ninguna zona
        counts[v.zoneId][v.state] += 1;
        if (v.state === 'AVAILABLE') counts[v.zoneId][v.type === 'BIKE' ? 'availableBikes' : 'availableScooters'] += 1;
      }
      return counts;
    }

    /** Los últimos n eventos aplicados, del más reciente al más antiguo. */
    recent(n) {
      const out = [];
      for (let i = this.cursor - 1; i >= 0 && out.length < n; i--) out.push(this.events[i]);
      return out;
    }
  }

  /** Horas con déficit (regla del enunciado) que están ocurriendo en el instante t. */
  function hotspotsAt(rows, t) {
    return rows.filter((r) => r.hotspot && r.hourStart <= t && t < r.hourStart + HOUR_MS);
  }

  /** Posición fija pero "repartida" de cada vehículo dentro de su zona, para que no se amontonen. */
  function vehicleOffset(vehicleId, radiusMeters, latitude) {
    let hash = 2166136261;
    for (let i = 0; i < vehicleId.length; i++) {
      hash ^= vehicleId.charCodeAt(i);
      hash = Math.imul(hash, 16777619) >>> 0;
    }
    const angle = (hash % 360) * (Math.PI / 180);
    const distance = radiusMeters * (0.25 + ((hash >>> 9) % 70) / 100);
    return {
      dLat: (distance * Math.cos(angle)) / 111320,
      dLon: (distance * Math.sin(angle)) / (111320 * Math.cos((latitude * Math.PI) / 180)),
    };
  }

  /** Punto a mitad de camino entre a y b; fraction = 0 en a, 1 en b. */
  function interpolate(a, b, fraction) {
    const f = Math.min(1, Math.max(0, fraction));
    return { lat: a.lat + (b.lat - a.lat) * f, lon: a.lon + (b.lon - a.lon) * f };
  }

  const REASONS = { LOW_BATTERY: 'batería baja', BREAKDOWN: 'avería', EXCESS_USAGE: 'revisión', INSPECTION: 'revisión', VANDALISM: 'vandalismo' };

  /** Frase en castellano para el registro de actividad. */
  function describe(e, zoneName) {
    const p = e.payload;
    const zone = (id) => (zoneName && zoneName(id)) || id;
    const vehicle = p.vehicleId ? `${p.vehicleType === 'BIKE' ? 'Bici' : 'Patinete'} ${p.vehicleId}` : '';
    switch (e.eventType) {
      case 'UserRegistered': return `Alta de ${p.role === 'maintenance' ? 'técnico' : 'usuario'}: ${p.fullName}`;
      case 'VehiclePurchased': return `${vehicle} incorporado en ${zone(p.initialZoneId)}`;
      case 'VehicleReserved': return `${vehicle} reservado en ${zone(p.zoneId)}`;
      case 'ReservationCancelled': return `Reserva cancelada: ${vehicle} en ${zone(p.zoneId)}`;
      case 'ReservationExpired': return `Reserva caducada: ${vehicle} en ${zone(p.zoneId)}`;
      case 'RideStarted': return `${vehicle} sale de ${zone(p.originZoneId)}`;
      case 'RideFinished':
        return `${vehicle} llega a ${zone(p.destinationZoneId)} (${Math.round(p.durationSeconds / 60)} min, `
          + `${p.preliminaryCost.amount.toFixed(2)} €)`;
      case 'VehicleBatteryLow': return `${vehicle} con batería al ${p.batteryLevel} % en ${zone(p.zoneId)}`;
      case 'VehicleWithdrawnForMaintenance': return `${vehicle} retirado (${REASONS[p.reason] || p.reason})`;
      case 'VehicleReturnedToService': return `${vehicle} vuelve al servicio en ${zone(p.zoneId)}`;
      default: return e.eventType;
    }
  }

  const api = {
    HOUR_MS, STATES, parseJsonl, parseZoneHoursCsv, indexRides, timeRange, Replay, hotspotsAt,
    vehicleOffset, interpolate, describe,
  };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.UrbanMoveViewer = api;
})(typeof window !== 'undefined' ? window : globalThis);
