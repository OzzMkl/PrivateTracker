// What every map page shares: the map itself, its data, circles and frames. Needs maplibre-gl-csp.js.
(() => {
  'use strict';

  const EARTH_RADIUS_M = 6371008.8;
  const WORLD = { center: [-40, 20], zoom: 1.5 };

  function cssColor(name, fallback) {
    const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    return value || fallback;
  }

  /** The frame around every [lon, lat] given, as the map options take it; the whole world when none. */
  function initialView(points, maxZoom) {
    if (!points || !points.length) return { center: WORLD.center, zoom: WORLD.zoom };
    const bounds = new maplibregl.LngLatBounds(points[0], points[0]);
    points.forEach((point) => bounds.extend(point));
    return { bounds, fitBoundsOptions: { padding: 60, maxZoom } };
  }

  /**
   * The map in [container], with the tiles and attribution its data attributes name, already framed
   * on [points]: no tiles of a view nobody sees are asked for.
   */
  function create(container, points, maxZoom = 16) {
    // The CSP build runs its worker from this server's own file, never from a blob of code.
    maplibregl.setWorkerUrl(container.dataset.worker);
    const attribution = document.createElement('a');
    attribution.textContent = container.dataset.attribution || '';
    if (container.dataset.attributionUrl) {
      attribution.href = container.dataset.attributionUrl;
      attribution.target = '_blank';
      attribution.rel = 'noopener noreferrer';
    }
    const map = new maplibregl.Map({
      container,
      style: {
        version: 8,
        sources: {
          tiles: {
            type: 'raster',
            tiles: [container.dataset.tiles],
            tileSize: 256,
            maxzoom: 19,
            attribution: attribution.outerHTML,
          },
        },
        layers: [{ id: 'tiles', type: 'raster', source: 'tiles' }],
      },
      ...initialView(points, maxZoom),
      attributionControl: { compact: false },
      dragRotate: false,
      pitchWithRotate: false,
    });
    map.touchZoomRotate.disableRotation();
    map.addControl(new maplibregl.NavigationControl({ showCompass: false }), 'top-right');
    map.addControl(new maplibregl.ScaleControl({ unit: 'metric' }), 'bottom-left');
    return map;
  }

  /** The JSON a page left for its script in <script type="application/json" id=...>. */
  function data(id) {
    const element = document.getElementById(id);
    return element ? JSON.parse(element.textContent) : null;
  }

  /** A point [distanceM] from [from] towards [bearingDeg], on a sphere: as the server draws circles. */
  function destination(from, distanceM, bearingDeg) {
    const lat = (from[1] * Math.PI) / 180;
    const lon = (from[0] * Math.PI) / 180;
    const angle = distanceM / EARTH_RADIUS_M;
    const bearing = (bearingDeg * Math.PI) / 180;
    const lat2 = Math.asin(Math.sin(lat) * Math.cos(angle) + Math.cos(lat) * Math.sin(angle) * Math.cos(bearing));
    const lon2 = lon + Math.atan2(Math.sin(bearing) * Math.sin(angle) * Math.cos(lat), Math.cos(angle) - Math.sin(lat) * Math.sin(lat2));
    return [(((lon2 * 180) / Math.PI + 540) % 360) - 180, (lat2 * 180) / Math.PI];
  }

  /** A closed ring of [steps] points around [center]. */
  function circle(center, radiusM, steps = 64) {
    const ring = [];
    for (let i = 0; i < steps; i++) ring.push(destination(center, radiusM, (360 * i) / steps));
    ring.push(ring[0]);
    return ring;
  }

  /** Frames every [lon, lat] given; with none, the whole world. */
  function frame(map, coordinates, maxZoom = 16) {
    if (!coordinates.length) {
      map.jumpTo(WORLD);
      return;
    }
    const bounds = new maplibregl.LngLatBounds(coordinates[0], coordinates[0]);
    coordinates.forEach((point) => bounds.extend(point));
    map.fitBounds(bounds, { padding: 60, maxZoom, duration: 0 });
  }

  /** The geofences' fill and edge, under whatever a page draws next. */
  function addGeofences(map, geofences, opacity = 0.1) {
    const color = cssColor('--geofence', '#7c3aed');
    map.addSource('geofences', {
      type: 'geojson',
      data: {
        type: 'FeatureCollection',
        features: geofences.map((geofence) => ({
          type: 'Feature',
          properties: { name: geofence.name },
          geometry: { type: 'Polygon', coordinates: [geofence.outline] },
        })),
      },
    });
    map.addLayer({ id: 'geofences-fill', type: 'fill', source: 'geofences', paint: { 'fill-color': color, 'fill-opacity': opacity } });
    map.addLayer({ id: 'geofences-line', type: 'line', source: 'geofences', paint: { 'line-color': color, 'line-width': 2, 'line-dasharray': [2, 1.5] } });
  }

  /** A marker the page styles: a dot, and a label when [label] is given. */
  function markerElement(className, label) {
    const element = document.createElement('div');
    element.className = 'marker ' + className;
    const dot = document.createElement('span');
    dot.className = 'marker-dot';
    element.append(dot);
    if (label) {
      const text = document.createElement('span');
      text.className = 'marker-label';
      text.textContent = label;
      element.append(text);
    }
    return element;
  }

  const dateTime = new Intl.DateTimeFormat('es-MX', { dateStyle: 'medium', timeStyle: 'short' });
  const relative = new Intl.RelativeTimeFormat('es', { numeric: 'auto' });

  /** "hace 5 minutos", from epoch milliseconds. */
  function ago(millis) {
    if (millis == null) return 'nunca';
    const seconds = Math.round((millis - Date.now()) / 1000);
    if (Math.abs(seconds) < 60) return 'hace un momento';
    const minutes = Math.round(seconds / 60);
    if (Math.abs(minutes) < 60) return relative.format(minutes, 'minute');
    const hours = Math.round(minutes / 60);
    if (Math.abs(hours) < 24) return relative.format(hours, 'hour');
    return relative.format(Math.round(hours / 24), 'day');
  }

  window.PTMaps = { create, data, circle, frame, addGeofences, markerElement, cssColor, ago, dateTime };
})();
