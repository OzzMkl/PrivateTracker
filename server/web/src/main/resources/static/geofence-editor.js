// The geofence editor: taps on the map set a circle's center or a polygon's vertices; the form's
// hidden "shape" field carries what was drawn, and the server checks it when saving.
(() => {
  'use strict';

  const container = document.getElementById('map');
  const form = document.getElementById('geofence-form');
  const shapeField = document.getElementById('shape');
  if (!container || !form || !shapeField) return;

  const MAX_VERTICES = 50;
  const radiusField = document.getElementById('radius');
  const devices = PTMaps.data('devices-data') || [];
  const others = PTMaps.data('geofences-data') || [];
  let shape = JSON.parse(shapeField.value);
  shape.vertices = shape.vertices || [];

  function radius() {
    const value = Number(radiusField && radiusField.value);
    return Number.isFinite(value) && value > 0 ? value : 0;
  }

  function sync() {
    form.dataset.kind = shape.kind;
    // A hidden field is still checked by the browser: a polygon must not be held up by a radius it does not use.
    if (radiusField) radiusField.disabled = shape.kind !== 'circle';
    shape.radiusM = radius() || null;
    shapeField.value = JSON.stringify(shape);
  }

  function features() {
    const all = [];
    if (shape.kind === 'circle') {
      if (shape.center) {
        if (radius()) all.push({ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [PTMaps.circle(shape.center, radius())] } });
        all.push({ type: 'Feature', properties: { role: 'center' }, geometry: { type: 'Point', coordinates: shape.center } });
      }
    } else {
      const vertices = shape.vertices;
      if (vertices.length >= 3) {
        all.push({ type: 'Feature', properties: {}, geometry: { type: 'Polygon', coordinates: [[...vertices, vertices[0]]] } });
      } else if (vertices.length === 2) {
        all.push({ type: 'Feature', properties: {}, geometry: { type: 'LineString', coordinates: vertices } });
      }
      vertices.forEach((vertex, index) => {
        all.push({ type: 'Feature', properties: { role: index === 0 ? 'first' : 'vertex' }, geometry: { type: 'Point', coordinates: vertex } });
      });
    }
    return { type: 'FeatureCollection', features: all };
  }

  function redraw() {
    sync();
    const source = map.getSource('draft');
    if (source) source.setData(features());
  }

  function shapePoints() {
    if (shape.kind === 'circle') return shape.center && radius() ? PTMaps.circle(shape.center, radius(), 16) : [];
    return shape.vertices;
  }

  // On what is drawn; on a new geofence, on the devices, where one usually goes.
  const drawn = shapePoints();
  const map = PTMaps.create(container, drawn.length ? drawn : devices.map((device) => device.position));

  form.querySelectorAll('input[name="kind"]').forEach((input) => {
    input.addEventListener('change', () => {
      if (!input.checked) return;
      shape.kind = input.value;
      redraw();
    });
  });
  if (radiusField) radiusField.addEventListener('input', redraw);

  const undo = document.getElementById('undo-vertex');
  if (undo) undo.addEventListener('click', () => { shape.vertices.pop(); redraw(); });
  const clear = document.getElementById('clear-vertices');
  if (clear) clear.addEventListener('click', () => { shape.vertices = []; redraw(); });

  function syncDevices() {
    const chosen = form.querySelector('input[name="devices"]:checked');
    form.dataset.devices = chosen ? chosen.value : 'all';
  }
  form.querySelectorAll('input[name="devices"]').forEach((input) => input.addEventListener('change', syncDevices));
  // Ticking a device means "only these".
  form.querySelectorAll('input[name="device"]').forEach((input) => {
    input.addEventListener('change', () => {
      if (!input.checked) return;
      const some = form.querySelector('input[name="devices"][value="some"]');
      if (some) some.checked = true;
      syncDevices();
    });
  });
  syncDevices();
  form.addEventListener('submit', sync);

  map.on('click', (event) => {
    const point = [event.lngLat.lng, event.lngLat.lat];
    if (shape.kind === 'circle') {
      shape.center = point;
    } else if (shape.vertices.length < MAX_VERTICES) {
      shape.vertices.push(point);
    }
    redraw();
  });

  map.on('load', () => {
    PTMaps.addGeofences(map, others, 0.05);
    const accent = PTMaps.cssColor('--geofence', '#7c3aed');

    map.addSource('devices', {
      type: 'geojson',
      data: {
        type: 'FeatureCollection',
        features: devices.map((device) => ({ type: 'Feature', properties: { name: device.name }, geometry: { type: 'Point', coordinates: device.position } })),
      },
    });
    map.addLayer({
      id: 'devices',
      type: 'circle',
      source: 'devices',
      paint: { 'circle-radius': 5, 'circle-color': PTMaps.cssColor('--track', '#2563eb'), 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 2 },
    });

    map.addSource('draft', { type: 'geojson', data: features() });
    map.addLayer({ id: 'draft-fill', type: 'fill', source: 'draft', filter: ['==', ['geometry-type'], 'Polygon'], paint: { 'fill-color': accent, 'fill-opacity': 0.18 } });
    map.addLayer({
      id: 'draft-line',
      type: 'line',
      source: 'draft',
      filter: ['in', ['geometry-type'], ['literal', ['Polygon', 'LineString']]],
      paint: { 'line-color': accent, 'line-width': 3 },
    });
    map.addLayer({
      id: 'draft-points',
      type: 'circle',
      source: 'draft',
      filter: ['==', ['geometry-type'], 'Point'],
      paint: {
        'circle-radius': ['case', ['==', ['get', 'role'], 'first'], 7, 5],
        'circle-color': accent,
        'circle-stroke-color': '#ffffff',
        'circle-stroke-width': 2,
      },
    });
    map.getCanvas().style.cursor = 'crosshair';
    sync();
  });
})();
