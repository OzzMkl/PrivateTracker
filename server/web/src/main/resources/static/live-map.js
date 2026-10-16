// The main map: where each approved device last was, kept current by the live stream.
(() => {
  'use strict';

  const container = document.getElementById('map');
  if (!container) return;

  const list = document.getElementById('device-list');
  const toggle = document.getElementById('show-geofences');
  const markers = new Map();
  let devices = PTMaps.data('devices-data') || [];
  const geofences = PTMaps.data('geofences-data') || [];
  let ready = false;
  let framed = devices.length > 0;

  function framePoints() {
    const points = devices.map((device) => device.position);
    if (!points.length) geofences.forEach((geofence) => points.push(...geofence.outline));
    return points;
  }

  const map = PTMaps.create(container, framePoints());

  const STATUS = { ONLINE: 'En línea', STALE: 'Inactivo', OFFLINE: 'Sin conexión' };

  function accuracyAreas() {
    return {
      type: 'FeatureCollection',
      features: devices
        .filter((device) => device.accuracyM && device.accuracyM > 5)
        .map((device) => ({ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [PTMaps.circle(device.position, device.accuracyM, 48)] } })),
    };
  }

  function popupFor(device) {
    const box = document.createElement('div');
    const title = document.createElement('div');
    title.className = 'popup-title';
    title.textContent = device.name;
    box.append(title);
    const lines = [
      STATUS[device.status] || device.status,
      'Posición ' + PTMaps.ago(device.recordedAt) + ' (' + PTMaps.dateTime.format(new Date(device.recordedAt)) + ')',
    ];
    if (device.accuracyM) lines.push('Precisión ± ' + Math.round(device.accuracyM) + ' m');
    if (device.batteryPct != null) lines.push('Batería ' + device.batteryPct + ' %');
    lines.forEach((text) => {
      const line = document.createElement('div');
      line.className = 'popup-line';
      line.textContent = text;
      box.append(line);
    });
    const link = document.createElement('a');
    link.href = '/devices/' + encodeURIComponent(device.id) + '/history';
    link.textContent = 'Ver historial';
    box.append(link);
    return box;
  }

  function drawMarkers() {
    const seen = new Set();
    devices.forEach((device) => {
      seen.add(device.id);
      let entry = markers.get(device.id);
      if (!entry) {
        const element = PTMaps.markerElement('', device.name);
        // One card per marker, its content renewed in place: an open card stays open as news arrive.
        const popup = new maplibregl.Popup({ offset: 14, maxWidth: '280px' });
        const marker = new maplibregl.Marker({ element, anchor: 'center' }).setLngLat(device.position).setPopup(popup).addTo(map);
        entry = { marker, element, popup };
        markers.set(device.id, entry);
      }
      entry.element.className = 'marker ' + device.status.toLowerCase();
      entry.element.querySelector('.marker-label').textContent = device.name;
      entry.element.setAttribute('aria-label', device.name);
      entry.marker.setLngLat(device.position);
      entry.popup.setDOMContent(popupFor(device));
    });
    markers.forEach((entry, id) => {
      if (!seen.has(id)) {
        entry.marker.remove();
        markers.delete(id);
      }
    });
    map.getSource('accuracy').setData(accuracyAreas());
  }

  function drawList() {
    if (!list) return;
    list.replaceChildren();
    if (!devices.length) {
      const empty = document.createElement('li');
      empty.className = 'empty';
      empty.textContent = 'Ningún dispositivo ha enviado su posición todavía.';
      list.append(empty);
      return;
    }
    devices.forEach((device) => {
      const item = document.createElement('li');
      item.className = 'device-item';
      item.dataset.id = device.id;
      const focus = document.createElement('button');
      focus.type = 'button';
      focus.className = 'device-focus';
      const dot = document.createElement('span');
      dot.className = 'dot ' + device.status.toLowerCase();
      dot.setAttribute('aria-hidden', 'true');
      const name = document.createElement('span');
      name.className = 'device-name';
      name.textContent = device.name;
      const seen = document.createElement('span');
      seen.className = 'device-seen';
      seen.textContent = PTMaps.ago(device.recordedAt);
      focus.append(dot, name, seen);
      const history = document.createElement('a');
      history.href = '/devices/' + encodeURIComponent(device.id) + '/history';
      history.className = 'small';
      history.textContent = 'Historial';
      item.append(focus, history);
      list.append(item);
    });
    const updated = document.getElementById('updated-at');
    if (updated) updated.textContent = 'Actualizado a las ' + new Intl.DateTimeFormat('es-MX', { timeStyle: 'short' }).format(new Date());
  }

  // A tap on a name in the list flies there and opens its card.
  if (list) {
    list.addEventListener('click', (event) => {
      const button = event.target.closest('.device-focus');
      if (!button) return;
      const id = button.closest('.device-item').dataset.id;
      const device = devices.find((candidate) => candidate.id === id);
      const entry = markers.get(id);
      if (!device || !entry) return;
      map.flyTo({ center: device.position, zoom: Math.max(map.getZoom(), 15) });
      if (!entry.popup.isOpen()) entry.marker.togglePopup();
      if (window.matchMedia('(max-width: 760px)').matches) container.scrollIntoView({ behavior: 'smooth' });
    });
  }

  map.on('load', () => {
    PTMaps.addGeofences(map, geofences);
    map.addSource('accuracy', { type: 'geojson', data: accuracyAreas() });
    map.addLayer({
      id: 'accuracy',
      type: 'fill',
      source: 'accuracy',
      paint: { 'fill-color': PTMaps.cssColor('--track', '#2563eb'), 'fill-opacity': 0.12 },
    });
    ready = true;
    drawMarkers();
    if (toggle) {
      toggle.addEventListener('change', () => {
        const visibility = toggle.checked ? 'visible' : 'none';
        ['geofences-fill', 'geofences-line'].forEach((layer) => map.setLayoutProperty(layer, 'visibility', visibility));
      });
    }
  });

  document.addEventListener('pt:devices', (event) => {
    devices = event.detail;
    drawList();
    if (!ready) return;
    drawMarkers();
    // The first devices to appear on an empty map get framed; after that the owner's view stays.
    if (!framed && devices.length) {
      PTMaps.frame(map, framePoints());
      framed = true;
    }
  });

  // Relative times age even without news.
  window.setInterval(drawList, 60000);
})();
