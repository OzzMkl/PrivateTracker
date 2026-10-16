// The history page: a device's route over the chosen range, with where it started and ended.
(() => {
  'use strict';

  const container = document.getElementById('map');
  if (!container) return;

  const track = PTMaps.data('track-data') || { segments: [] };
  const map = PTMaps.create(container, track.segments.flat(), 17);

  /** Close enough for a few hundred meters: a flat projection around the first point. */
  function metersApart(a, b) {
    const dx = (a[0] - b[0]) * 111320 * Math.cos((a[1] * Math.PI) / 180);
    const dy = (a[1] - b[1]) * 110540;
    return Math.hypot(dx, dy);
  }

  map.on('load', () => {
    const lines = track.segments.filter((segment) => segment.length > 1);
    const lonely = track.segments.filter((segment) => segment.length === 1).map((segment) => segment[0]);
    const color = PTMaps.cssColor('--track', '#2563eb');

    map.addSource('track', {
      type: 'geojson',
      data: { type: 'Feature', properties: {}, geometry: { type: 'MultiLineString', coordinates: lines } },
    });
    map.addLayer({
      id: 'track-casing',
      type: 'line',
      source: 'track',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#ffffff', 'line-width': 7, 'line-opacity': 0.85 },
    });
    map.addLayer({
      id: 'track',
      type: 'line',
      source: 'track',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': color, 'line-width': 4 },
    });
    // A stretch of one position has no line to draw: a dot instead.
    map.addSource('track-points', {
      type: 'geojson',
      data: {
        type: 'FeatureCollection',
        features: lonely.map((point) => ({ type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: point } })),
      },
    });
    map.addLayer({
      id: 'track-points',
      type: 'circle',
      source: 'track-points',
      paint: { 'circle-radius': 5, 'circle-color': color, 'circle-stroke-color': '#ffffff', 'circle-stroke-width': 2 },
    });

    // A day that starts and ends at home gets one marker, not two on top of each other.
    const together = track.start && track.end && metersApart(track.start, track.end) < 50;
    if (track.start) {
      new maplibregl.Marker({ element: PTMaps.markerElement('route-start', together ? 'Inicio y fin' : 'Inicio'), anchor: 'center' })
        .setLngLat(track.start).addTo(map);
    }
    if (track.end && !together) {
      new maplibregl.Marker({ element: PTMaps.markerElement('route-end', 'Fin'), anchor: 'center' }).setLngLat(track.end).addTo(map);
    }
  });
})();
