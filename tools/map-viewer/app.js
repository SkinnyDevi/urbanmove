/*
 * UrbanMove · visor de mapa · PARTE VISUAL
 *
 * 1. Carga los ficheros que genera tools/synthetic-generator (events.jsonl, seed/zones.json y el CSV).
 * 2. Dibuja las zonas y los vehículos en un mapa de Leaflet.
 * 3. Mueve el tiempo con la barra o el botón de reproducir, y en cada instante pide a viewer-core.js
 *    el estado de la flota y lo pinta.
 */
(function () {
  'use strict';

  const V = window.UrbanMoveViewer;
  const MINUTE = 60 * 1000;
  const TICK_MS = 100;   // cada cuánto se redibuja al reproducir
  const LABEL_NAMES_ZOOM = 15;   // a partir de este zoom, las etiquetas de zona muestran el nombre
  const STATE_LABEL = {
    AVAILABLE: 'Disponibles', RESERVED: 'Reservados', IN_USE: 'En uso', LOW_BATTERY: 'Batería baja', WITHDRAWN: 'Retirados',
  };
  const STATE_SINGULAR = {
    AVAILABLE: 'disponible', RESERVED: 'reservado', IN_USE: 'en uso', LOW_BATTERY: 'batería baja', WITHDRAWN: 'retirado',
  };
  const dayFormat = new Intl.DateTimeFormat('es-ES', { timeZone: 'UTC', weekday: 'long', day: 'numeric', month: 'long' });
  const shortFormat = new Intl.DateTimeFormat('es-ES', { timeZone: 'UTC', weekday: 'short', day: 'numeric', month: 'short' });
  const hourFormat = new Intl.DateTimeFormat('es-ES', { timeZone: 'UTC', hour: '2-digit', minute: '2-digit' });

  const $ = (id) => document.getElementById(id);
  const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;
  const ui = {
    folderInput: $('folder-input'), filesInput: $('files-input'), status: $('load-status'), dashboard: $('dashboard'),
    slider: $('slider'), play: $('play'), back: $('back'), forward: $('forward'), speed: $('speed'),
    clockDay: $('clock-day'), clockTime: $('clock-time'), kpis: $('kpis'), alerts: $('alerts'),
    zonesBody: document.querySelector('#zones-table tbody'), hotspotsCard: $('hotspots-card'),
    hotspots: $('hotspots'), feed: $('feed'),
  };

  const app = {
    map: null, data: null, replay: null, t: 0, timer: null,
    zoneLayers: new Map(),     // zoneId -> { circle, label }
    vehicleMarkers: new Map(), // vehicleId -> marker
  };

  // ================================================================ mapa base

  function createMap() {
    if (typeof L === 'undefined') {
      const message = document.createElement('div');
      message.id = 'map-message';
      message.textContent = 'No se ha podido cargar la librería del mapa (Leaflet). '
        + 'Comprueba tu conexión a internet y recarga la página.';
      $('map').style.position = 'relative';
      $('map').appendChild(message);
      return null;
    }
    const map = L.map('map', { zoomControl: true }).setView([28.115, -15.435], 13);
    L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
      maxZoom: 19,
      attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
    }).addTo(map);
    return map;
  }

  // ================================================================ carga de ficheros

  /** Busca los ficheros que necesitamos dentro de lo que eligió el usuario. */
  function pickFiles(fileList) {
    const files = Array.from(fileList);
    const path = (f) => (f.webkitRelativePath || f.name).replace(/\\/g, '/');
    // events.jsonl hay muchos (uno por partición en raw/); queremos el de la raíz, que tiene todos
    const events = files
      .filter((f) => f.name === 'events.jsonl' && !path(f).includes('/raw/'))
      .sort((a, b) => path(a).length - path(b).length)[0];
    const zones = files.find((f) => path(f).endsWith('seed/zones.json')) || files.find((f) => f.name === 'zones.json');
    const csv = files.find((f) => f.name === 'ground_truth_zone_hours.csv');
    return { events, zones, csv };
  }

  async function load(fileList) {
    stop();
    const { events, zones, csv } = pickFiles(fileList);
    if (!events || !zones) {
      setStatus('Faltan ficheros: ' + [!events && 'events.jsonl', !zones && 'seed/zones.json'].filter(Boolean).join(' y ')
        + '. Elige la carpeta out completa.', true);
      return;
    }
    setStatus('Leyendo eventos…');
    try {
      const [eventsText, zonesText, csvText] = await Promise.all([
        events.text(), zones.text(), csv ? csv.text() : Promise.resolve(null),
      ]);
      const parsedEvents = V.parseJsonl(eventsText);
      const zoneList = JSON.parse(zonesText);
      app.data = {
        events: parsedEvents,
        zones: new Map(zoneList.map((z) => [z.zoneId, z])),
        rides: V.indexRides(parsedEvents),
        rows: csvText ? V.parseZoneHoursCsv(csvText) : [],
        range: V.timeRange(parsedEvents),
      };
    } catch (error) {
      setStatus('No he podido leer los ficheros: ' + error.message, true);
      return;
    }
    app.replay = new V.Replay(app.data.events);
    setStatus(`${app.data.events.length.toLocaleString('es-ES')} eventos cargados · ${app.data.zones.size} zonas`
      + (app.data.rows.length ? '' : ' · sin CSV de déficits'));
    setupDashboard();
  }

  function setStatus(text, isError) {
    ui.status.textContent = text;
    ui.status.classList.toggle('error', Boolean(isError));
  }

  // ================================================================ preparar panel y mapa

  function setupDashboard() {
    const { range, zones, rows } = app.data;
    ui.dashboard.hidden = false;
    ui.slider.min = range.start;
    ui.slider.max = range.end;
    ui.slider.step = 5 * MINUTE;
    app.t = range.start + 7 * 60 * MINUTE;   // empezamos el primer día a las 7:00, cuando arranca la demanda

    if (app.map) {
      app.zoneLayers.forEach(({ circle }) => circle.remove());
      app.vehicleMarkers.forEach((marker) => marker.remove());
      app.zoneLayers.clear();
      app.vehicleMarkers.clear();
      for (const zone of zones.values()) {
        const circle = L.circle([zone.center.lat, zone.center.lon], {
          radius: zone.radiusMeters, weight: 3, fillOpacity: 0.12,
        }).addTo(app.map);
        circle.bindTooltip('', { permanent: true, direction: 'center', className: 'zone-label' });
        app.zoneLayers.set(zone.zoneId, { circle, label: '' });
      }
      app.map.fitBounds(L.featureGroup([...app.zoneLayers.values()].map((z) => z.circle)).getBounds().pad(0.1));
      app.map.off('zoomend', render);
      app.map.on('zoomend', render);   // al acercar o alejar cambian las etiquetas
    }

    ui.zonesBody.replaceChildren(...[...zones.values()].map((zone) => {
      const row = document.createElement('tr');
      row.dataset.zone = zone.zoneId;
      row.innerHTML = '<td></td><td></td><td></td><td></td><td></td>';
      row.cells[0].textContent = zone.name;
      return row;
    }));

    const hotspots = rows.filter((r) => r.hotspot);
    ui.hotspotsCard.hidden = hotspots.length === 0;
    ui.hotspots.replaceChildren(...hotspots.map((h) => {
      const item = document.createElement('li');
      const button = document.createElement('button');
      button.type = 'button';
      const zone = zones.get(h.zoneId);
      button.innerHTML = '<strong></strong> · <span></span><br><small></small>';
      button.querySelector('strong').textContent = `${shortFormat.format(h.hourStart)} ${hourFormat.format(h.hourStart)}`;
      button.querySelector('span').textContent = zone ? zone.name : h.zoneId;
      button.querySelector('small').textContent = `${plural(h.availableAtEnd, 'disponible', 'disponibles')}, `
        + `${plural(h.demandSignals, 'señal', 'señales')} de demanda, ${h.unmetRequests} sin vehículo`;
      button.addEventListener('click', () => {
        stop();
        app.t = h.hourStart + 55 * MINUTE;
        render();
        if (app.map && zone) app.map.flyTo([zone.center.lat, zone.center.lon], 15, { duration: 0.8 });
      });
      item.appendChild(button);
      return item;
    }));

    render();
  }

  // ================================================================ dibujar un instante

  function zoneName(id) {
    const zone = app.data.zones.get(id);
    return zone ? zone.name : id;
  }

  function vehiclePosition(vehicle) {
    const { zones, rides } = app.data;
    if (vehicle.state === 'IN_USE' && vehicle.rideId) {
      const ride = rides.get(vehicle.rideId);
      const from = zones.get(ride.origin);
      const to = zones.get(ride.destination);
      if (from && to && ride.end) {   // en ruta: lo dibujamos avanzando entre las dos zonas
        const p = V.interpolate(from.center, to.center, (app.t - ride.start) / (ride.end - ride.start));
        return [p.lat, p.lon];
      }
    }
    const zone = zones.get(vehicle.zoneId);
    if (!zone) return null;
    const offset = V.vehicleOffset(vehicle.id, zone.radiusMeters, zone.center.lat);
    return [zone.center.lat + offset.dLat, zone.center.lon + offset.dLon];
  }

  // Colores directos (no var(--...)): los círculos del mapa son SVG y sus atributos no entienden variables CSS
  function zoneColor(available) {
    if (available < 3) return '#d64545';   // menos de 3: el umbral de déficit del enunciado
    if (available < 6) return '#d39a00';
    return '#1f9d55';
  }

  function render() {
    const { zones, rows } = app.data;
    app.replay.seek(app.t);
    ui.slider.value = app.t;
    ui.clockDay.textContent = dayFormat.format(app.t);
    ui.clockTime.textContent = hourFormat.format(app.t);

    const counts = app.replay.zoneCounts([...zones.keys()]);
    const hotspotsNow = V.hotspotsAt(rows, app.t);
    const hotZones = new Set(hotspotsNow.map((h) => h.zoneId));

    // ---- mapa
    if (app.map) {
      for (const [zoneId, layer] of app.zoneLayers) {
        const c = counts[zoneId];
        const color = zoneColor(c.AVAILABLE);
        layer.circle.setStyle({ color, fillColor: color });
        const element = layer.circle.getElement();
        if (element) element.classList.toggle('zone-hotspot', hotZones.has(zoneId));
        // con la ciudad entera solo cabe el número; al acercar el zoom se ve también el nombre
        const label = app.map.getZoom() >= LABEL_NAMES_ZOOM ? `${zoneName(zoneId)} · ${c.AVAILABLE}` : String(c.AVAILABLE);
        if (label !== layer.label) {
          layer.circle.setTooltipContent(label);
          layer.label = label;
        }
      }
      for (const vehicle of app.replay.vehicles.values()) {
        const position = vehiclePosition(vehicle);
        if (!position) continue;
        let marker = app.vehicleMarkers.get(vehicle.id);
        if (!marker) {
          marker = L.marker(position, {
            icon: L.divIcon({ className: '', html: '<div class="veh"></div>', iconSize: [13, 13] }),
            keyboard: false,
          }).addTo(app.map);
          app.vehicleMarkers.set(vehicle.id, marker);
        } else {
          marker.setLatLng(position);
        }
        const element = marker.getElement() && marker.getElement().firstChild;
        if (element) {
          element.className = `veh veh-${vehicle.type} st-${vehicle.state}`;
          element.title = `${vehicle.id} · ${vehicle.type === 'BIKE' ? 'bici' : 'patinete'} · `
            + STATE_SINGULAR[vehicle.state];
        }
      }
    }

    // ---- indicadores
    const totals = app.replay.totals();
    ui.kpis.replaceChildren(...V.STATES.map((state) => {
      const item = document.createElement('li');
      item.innerHTML = '<strong></strong><span></span>';
      item.firstChild.textContent = totals[state];
      item.lastChild.textContent = STATE_LABEL[state];
      return item;
    }));

    // ---- avisos de déficit en esta hora
    ui.alerts.replaceChildren(...hotspotsNow.map((h) => {
      const div = document.createElement('div');
      div.className = 'alert';
      div.textContent = `Déficit en ${zoneName(h.zoneId)}: ${h.demandSignals} reservas o viajes en esta hora y solo `
        + `${plural(h.availableAtEnd, 'vehículo disponible', 'vehículos disponibles')} al final `
        + `(${plural(h.unmetRequests, 'persona', 'personas')} sin vehículo). `
        + 'Aquí la Lambda de recolocación debería crear una orden.';
      return div;
    }));

    // ---- tabla de zonas
    for (const row of ui.zonesBody.rows) {
      const c = counts[row.dataset.zone];
      row.classList.toggle('is-hotspot', hotZones.has(row.dataset.zone));
      row.cells[1].innerHTML = '<span class="pill"></span>';
      const pill = row.cells[1].firstChild;
      pill.textContent = `${c.availableBikes} / ${c.availableScooters}`;
      pill.style.background = zoneColor(c.AVAILABLE);
      row.cells[2].textContent = c.RESERVED;
      row.cells[3].textContent = c.LOW_BATTERY;
      row.cells[4].textContent = c.WITHDRAWN;
    }

    // ---- últimos eventos
    ui.feed.replaceChildren(...app.replay.recent(8).map((event) => {
      const item = document.createElement('li');
      const time = document.createElement('time');
      time.textContent = hourFormat.format(event._t);
      item.append(time, V.describe(event, zoneName));
      return item;
    }));
  }

  // ================================================================ controles de tiempo

  function moveTo(t) {
    const { start, end } = app.data.range;
    app.t = Math.min(end, Math.max(start, t));
    render();
  }

  function play() {
    if (app.t >= app.data.range.end) app.t = app.data.range.start;
    ui.play.textContent = '⏸ Pausa';
    app.timer = setInterval(() => {
      const minutesPerSecond = Number(ui.speed.value);
      moveTo(app.t + (minutesPerSecond * MINUTE * TICK_MS) / 1000);
      if (app.t >= app.data.range.end) stop();
    }, TICK_MS);
  }

  function stop() {
    if (app.timer) clearInterval(app.timer);
    app.timer = null;
    ui.play.textContent = '▶ Reproducir';
  }

  ui.play.addEventListener('click', () => (app.timer ? stop() : play()));
  ui.back.addEventListener('click', () => moveTo(app.t - 60 * MINUTE));
  ui.forward.addEventListener('click', () => moveTo(app.t + 60 * MINUTE));
  ui.slider.addEventListener('input', () => {
    stop();
    moveTo(Number(ui.slider.value));
  });
  ui.folderInput.addEventListener('change', () => load(ui.folderInput.files));
  ui.filesInput.addEventListener('change', () => load(ui.filesInput.files));

  app.map = createMap();
  window.urbanMoveViewerApp = app;   // para poder inspeccionarlo desde la consola del navegador
})();
