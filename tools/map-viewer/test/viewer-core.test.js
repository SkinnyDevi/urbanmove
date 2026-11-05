// Tests de la lógica del visor. Ejecutar desde la raíz del repo:  node --test tools/map-viewer/test/viewer-core.test.js
const test = require('node:test');
const assert = require('node:assert/strict');
const V = require('../viewer-core.js');

const T0 = Date.parse('2026-11-09T00:00:00.000Z');
const at = (minutes) => new Date(T0 + minutes * 60000).toISOString();
const ev = (minutes, eventType, payload) => JSON.stringify({ timestamp: at(minutes), eventType, payload });

// Una pequeña historia: dos vehículos, un viaje, una cancelación y un ciclo de mantenimiento.
const JSONL = [
  ev(-600, 'VehiclePurchased', { vehicleId: 'veh-0001', vehicleType: 'SCOOTER', initialZoneId: 'tafira' }),
  ev(-590, 'VehiclePurchased', { vehicleId: 'veh-0002', vehicleType: 'BIKE', initialZoneId: 'triana' }),
  ev(480, 'VehicleReserved', { vehicleId: 'veh-0001', vehicleType: 'SCOOTER', zoneId: 'tafira' }),
  ev(485, 'RideStarted', { rideId: 'ride-1', vehicleId: 'veh-0001', vehicleType: 'SCOOTER', originZoneId: 'tafira' }),
  ev(500, 'VehicleReserved', { vehicleId: 'veh-0002', vehicleType: 'BIKE', zoneId: 'triana' }),
  ev(505, 'ReservationCancelled', { vehicleId: 'veh-0002', vehicleType: 'BIKE', zoneId: 'triana' }),
  ev(515, 'RideFinished', { rideId: 'ride-1', vehicleId: 'veh-0001', vehicleType: 'SCOOTER', originZoneId: 'tafira',
    destinationZoneId: 'triana', durationSeconds: 1800, preliminaryCost: { amount: 6.1, currency: 'EUR' } }),
  ev(516, 'VehicleBatteryLow', { vehicleId: 'veh-0001', vehicleType: 'SCOOTER', batteryLevel: 9, zoneId: 'triana' }),
  ev(560, 'VehicleWithdrawnForMaintenance', { vehicleId: 'veh-0001', vehicleType: 'SCOOTER', reason: 'LOW_BATTERY', zoneId: 'triana' }),
  ev(740, 'VehicleReturnedToService', { vehicleId: 'veh-0001', vehicleType: 'SCOOTER', zoneId: 'triana' }),
].reverse().join('\n') + '\n';   // al revés a propósito: parseJsonl debe ordenarlos

const events = V.parseJsonl(JSONL);
const minute = (m) => T0 + m * 60000;

test('parseJsonl ordena por tiempo y añade _t', () => {
  assert.equal(events.length, 10);
  assert.equal(events[0].eventType, 'VehiclePurchased');
  assert.ok(events.every((e, i) => i === 0 || events[i - 1]._t <= e._t));
});

test('el estado de cada vehículo sigue los eventos', () => {
  const replay = new V.Replay(events);
  const state = (m, id) => replay.seek(minute(m)).vehicles.get(id).state;
  assert.equal(state(0, 'veh-0001'), 'AVAILABLE');
  assert.equal(state(482, 'veh-0001'), 'RESERVED');
  assert.equal(state(490, 'veh-0001'), 'IN_USE');
  assert.equal(state(502, 'veh-0002'), 'RESERVED');
  assert.equal(state(506, 'veh-0002'), 'AVAILABLE');
  assert.equal(state(515, 'veh-0001'), 'AVAILABLE');
  assert.equal(state(516, 'veh-0001'), 'LOW_BATTERY');
  assert.equal(state(600, 'veh-0001'), 'WITHDRAWN');
  assert.equal(state(800, 'veh-0001'), 'AVAILABLE');
});

test('al terminar el viaje el vehículo cambia de zona', () => {
  const replay = new V.Replay(events);
  assert.equal(replay.seek(minute(490)).vehicles.get('veh-0001').zoneId, 'tafira');
  assert.equal(replay.seek(minute(520)).vehicles.get('veh-0001').zoneId, 'triana');
});

test('retroceder en el tiempo da el mismo resultado que llegar desde el principio', () => {
  const forward = new V.Replay(events).seek(minute(490));
  const backward = new V.Replay(events).seek(minute(800)).seek(minute(490));
  assert.deepEqual([...backward.vehicles.entries()], [...forward.vehicles.entries()]);
});

test('zoneCounts no cuenta en ninguna zona los vehículos que van en ruta', () => {
  const counts = new V.Replay(events).seek(minute(490)).zoneCounts(['tafira', 'triana']);
  assert.equal(counts.tafira.AVAILABLE, 0);
  assert.equal(counts.triana.AVAILABLE, 1);
  assert.equal(counts.triana.availableBikes, 1);
  assert.equal(counts.triana.availableScooters, 0);
});

test('totals suma toda la flota por estado', () => {
  const totals = new V.Replay(events).seek(minute(600)).totals();
  assert.deepEqual(totals, { AVAILABLE: 1, RESERVED: 0, IN_USE: 0, LOW_BATTERY: 0, WITHDRAWN: 1 });
});

test('indexRides conoce origen, destino, inicio y fin de cada viaje', () => {
  const ride = V.indexRides(events).get('ride-1');
  assert.equal(ride.origin, 'tafira');
  assert.equal(ride.destination, 'triana');
  assert.equal(ride.end - ride.start, 30 * 60000);
});

test('timeRange empieza el primer día de uso, no el día de las altas', () => {
  const range = V.timeRange(events);
  assert.equal(range.start, T0);
  assert.equal(range.end, minute(740));
});

test('parseZoneHoursCsv entiende true/false y hotspotsAt filtra por hora', () => {
  const rows = V.parseZoneHoursCsv(
    'hourStart,zoneId,availableAtEnd,demandSignals,unmetRequests,hotspot\r\n'
    + '2026-11-09T07:00:00.000Z,siete-palmas,1,14,5,true\r\n'
    + '2026-11-09T07:00:00.000Z,tafira,9,3,0,false\r\n'
    + '2026-11-09T08:00:00.000Z,siete-palmas,2,12,3,True\r\n');
  assert.equal(rows.length, 3);
  assert.equal(rows[2].hotspot, true);
  assert.deepEqual(V.hotspotsAt(rows, minute(7 * 60 + 30)).map((r) => r.zoneId), ['siete-palmas']);
  assert.equal(V.hotspotsAt(rows, minute(9 * 60 + 5)).length, 0);
});

test('vehicleOffset reparte los vehículos dentro del radio de la zona y siempre igual', () => {
  const a = V.vehicleOffset('veh-0042', 500, 28.1);
  assert.deepEqual(a, V.vehicleOffset('veh-0042', 500, 28.1));
  const meters = Math.hypot(a.dLat * 111320, a.dLon * 111320 * Math.cos((28.1 * Math.PI) / 180));
  assert.ok(meters <= 500, `${meters} m`);
  assert.notDeepEqual(a, V.vehicleOffset('veh-0043', 500, 28.1));
});

test('interpolate se queda entre los dos puntos', () => {
  const a = { lat: 0, lon: 0 };
  const b = { lat: 10, lon: 20 };
  assert.deepEqual(V.interpolate(a, b, 0.5), { lat: 5, lon: 10 });
  assert.deepEqual(V.interpolate(a, b, 2), b);
});

test('describe produce frases legibles', () => {
  const names = { tafira: 'Campus de Tafira', triana: 'Triana' };
  const text = V.describe(events.find((e) => e.eventType === 'RideFinished'), (id) => names[id]);
  assert.equal(text, 'Patinete veh-0001 llega a Triana (30 min, 6.10 €)');
});
