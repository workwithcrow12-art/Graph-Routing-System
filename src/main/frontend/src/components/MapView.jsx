import { useEffect, useMemo, useRef, useState } from 'react';
import Icon from './Icon.jsx';
import NetworkCanvas from './NetworkCanvas.jsx';
import { formatDistance, formatDuration } from '../utils/route.js';
import { EMPTY_FEATURES, geometryBounds, linePosition, roadFeatures, routeFeatures } from '../utils/map-view.js';
import 'maplibre-gl/dist/maplibre-gl.css';
import '../styles/map-view.css';
import workerUrl from 'maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url';

const EMPTY = [];
const THANH_XUAN = [[105.779, 20.981], [105.832, 21.016]];
const LINE_LAYOUT = { 'line-cap': 'round', 'line-join': 'round' };

export function mapStyle() {
  const width = ['interpolate', ['linear'], ['zoom'], 12, ['get', 'width'], 16, ['*', ['get', 'width'], 4], 19, ['*', ['get', 'width'], 13]];
  const casingWidth = ['interpolate', ['linear'], ['zoom'], 12, ['+', ['get', 'width'], 2.3],
    16, ['+', ['*', ['get', 'width'], 4], 2.3], 19, ['+', ['*', ['get', 'width'], 13], 2.3]];
  return {
    version: 8,
    sources: {
      roads: { type: 'geojson', data: EMPTY_FEATURES },
      labels: { type: 'geojson', data: EMPTY_FEATURES },
      nodes: { type: 'geojson', data: EMPTY_FEATURES },
      alternatives: { type: 'geojson', data: EMPTY_FEATURES },
      selected: { type: 'geojson', data: EMPTY_FEATURES, lineMetrics: true },
    },
    layers: [
      { id: 'ground', type: 'background', paint: { 'background-color': '#F4E8C9' } },
      { id: 'road-casing', type: 'line', source: 'roads', layout: LINE_LAYOUT,
        paint: { 'line-color': '#D8C696', 'line-width': casingWidth } },
      { id: 'road-fill', type: 'line', source: 'roads', layout: LINE_LAYOUT,
        paint: { 'line-color': '#FFFBF0', 'line-width': width } },
      { id: 'nodes', type: 'circle', source: 'nodes', layout: { visibility: 'none' },
        paint: { 'circle-radius': ['interpolate', ['linear'], ['zoom'], 12, 1.2, 18, 4],
          'circle-color': '#0E7C7B', 'circle-opacity': 0.6, 'circle-stroke-color': '#FFFBF0', 'circle-stroke-width': 1 } },
      { id: 'road-labels', type: 'symbol', source: 'labels', minzoom: 13,
        filter: ['!=', ['get', 'name'], ''],
        layout: { 'symbol-placement': 'point', 'text-field': ['get', 'name'],
          'text-font': ['Inter', 'Arial'], 'text-size': ['interpolate', ['linear'], ['zoom'], 13, 12.5, 17, 15],
          'text-max-width': 12, 'text-padding': 18, 'symbol-sort-key': ['*', -1, ['get', 'width']] },
        paint: { 'text-color': '#4A6665', 'text-halo-color': '#FFFBF0', 'text-halo-width': 2 } },
      { id: 'alternative-casing', type: 'line', source: 'alternatives', layout: LINE_LAYOUT,
        filter: ['==', ['get', 'selected'], false], paint: { 'line-color': '#FFFBF0', 'line-width': 10 } },
      { id: 'alternative-routes', type: 'line', source: 'alternatives', layout: LINE_LAYOUT,
        filter: ['==', ['get', 'selected'], false], paint: { 'line-color': '#8C9E96', 'line-width': 6 } },
      { id: 'route-casing', type: 'line', source: 'selected', layout: LINE_LAYOUT,
        paint: { 'line-color': '#FFFBF0', 'line-width': 13 } },
      { id: 'route-outline', type: 'line', source: 'selected', layout: LINE_LAYOUT,
        paint: { 'line-color': '#0B5C5E', 'line-width': 9 } },
      { id: 'route-line', type: 'line', source: 'selected', layout: LINE_LAYOUT,
        paint: { 'line-color': '#0E7C7B', 'line-width': 6 } },
      { id: 'route-dashes', type: 'line', source: 'selected', layout: LINE_LAYOUT,
        paint: { 'line-color': '#FFFBF0', 'line-width': 2.1, 'line-dasharray': [0, 4, 2, 4] } },
    ],
  };
}

function viewPadding(container) {
  const { width, height } = container.getBoundingClientRect();
  if (width < 768) return { left: 32, right: 55, top: Math.min(235, height * 0.28), bottom: Math.min(330, height * 0.4) };
  return { left: Math.min(445, width * 0.4), right: 100, top: 115, bottom: 90 };
}

function fitMap(map, coordinates, container, reducedMotion, duration = 900) {
  const bounds = geometryBounds(coordinates);
  if (bounds) map.fitBounds(bounds, { padding: viewPadding(container), maxZoom: 17.5,
    duration: reducedMotion ? 0 : duration, retainPadding: false });
}

function useReducedMotion() {
  const [reduced, setReduced] = useState(() => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches || false);
  useEffect(() => {
    const query = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    const change = (event) => setReduced(event.matches);
    query?.addEventListener('change', change);
    return () => query?.removeEventListener('change', change);
  }, []);
  return reduced;
}

export default function MapView({ map, routes = EMPTY, selectedRoute = 0, markers = EMPTY,
  mode = 'motorcycling', onPick, is3D = true, showTraffic = false, showNodes = false,
  navigating = false, onLocationError, active = true }) {
  const container = useRef(null), instance = useRef(null), library = useRef(null), locationMarker = useRef(null);
  const latest = useRef(null);
  const reducedMotion = useReducedMotion();
  const [fallback, setFallback] = useState(() => !window.WebGLRenderingContext && !window.WebGL2RenderingContext);
  const [ready, setReady] = useState(false), [bearing, setBearing] = useState(0);
  const [locating, setLocating] = useState(false), [locationError, setLocationError] = useState('');
  const roads = useMemo(() => roadFeatures(map), [map]);
  const route = routes[selectedRoute];
  latest.current = { onPick, is3D, reducedMotion, map, routes };

  useEffect(() => {
    if (fallback || !container.current) return;
    let disposed = false, view, resizeObserver, timeout, resizeTimer;
    const resize = () => {
      view?.resize();
      clearTimeout(resizeTimer);
      resizeTimer = setTimeout(() => {
        const coordinates = latest.current.routes.flatMap((item) => item.geometry?.coordinates || []);
        if (!disposed && view?.loaded() && coordinates.length) fitMap(view, coordinates, container.current, true);
      }, 120);
    };
    const fail = () => { if (!disposed) { setReady(false); setFallback(true); } };
    import('maplibre-gl').then((module) => {
      if (disposed) return;
      const gl = module.default || module;
      gl.setWorkerUrl(workerUrl);
      library.current = gl;
      const bounds = latest.current.map?.bounds;
      view = new gl.Map({
        container: container.current, style: mapStyle(), attributionControl: false,
        bounds: bounds ? [[bounds.west, bounds.south], [bounds.east, bounds.north]] : THANH_XUAN,
        fitBoundsOptions: { padding: viewPadding(container.current) },
        pitch: latest.current.is3D ? 55 : 0, bearing: latest.current.is3D ? -12 : 0,
        minZoom: 11, maxZoom: 20, maxPitch: 65, renderWorldCopies: false,
        canvasContextAttributes: { antialias: true },
      });
      instance.current = view;
      view.addControl(new gl.ScaleControl({ maxWidth: 105, unit: 'metric' }), 'bottom-left');
      view.getCanvas().setAttribute('aria-label', 'Bản đồ đường phố Thanh Xuân. Dùng phím mũi tên để di chuyển, cộng và trừ để phóng to hoặc thu nhỏ.');
      view.on('load', () => {
        clearTimeout(timeout);
        if (!disposed) { setReady(true); setBearing(view.getBearing()); }
      });
      view.on('rotate', () => { if (!disposed) setBearing(view.getBearing()); });
      view.on('click', (event) => latest.current.onPick?.({ lat: event.lngLat.lat, lng: event.lngLat.lng }));
      view.on('webglcontextlost', fail);
      view.on('error', (event) => {
        if (/webgl|context|worker/i.test(event.error?.message || '')) fail();
      });
      if (window.ResizeObserver) {
        resizeObserver = new window.ResizeObserver(resize);
        resizeObserver.observe(container.current);
      } else window.addEventListener('resize', resize);
      timeout = setTimeout(fail, 15000);
    }).catch(fail);
    return () => {
      disposed = true;
      clearTimeout(timeout);
      clearTimeout(resizeTimer);
      resizeObserver?.disconnect();
      window.removeEventListener('resize', resize);
      locationMarker.current?.remove(); locationMarker.current = null;
      instance.current = null;
      view?.remove();
    };
  }, [fallback]);

  useEffect(() => {
    if (!ready) return;
    const view = instance.current;
    view.getSource('roads').setData(roads);
    const labels = new Map();
    for (const feature of roads.features) {
      if (!feature.properties.name) continue;
      const coordinates = feature.geometry.coordinates;
      const length = coordinates.slice(1).reduce((sum, point, index) => sum + Math.hypot(point[0] - coordinates[index][0], point[1] - coordinates[index][1]), 0);
      const previous = labels.get(feature.properties.name);
      if (!previous || length > previous.length) labels.set(feature.properties.name, { length, feature });
    }
    view.getSource('labels').setData({ type: 'FeatureCollection', features: [...labels.values()].map(({ feature }) => ({
      type: 'Feature', properties: feature.properties, geometry: { type: 'Point', coordinates: linePosition(feature.geometry.coordinates, 0.5) },
    })) });
    view.getSource('nodes').setData({ type: 'FeatureCollection', features: (map?.places || []).map((place) => ({
      type: 'Feature', properties: {}, geometry: { type: 'Point', coordinates: [place.longitude, place.latitude] },
    })) });
    if (!routes.length) fitMap(view, roads.features.flatMap((feature) => feature.geometry.coordinates), container.current, true);
  }, [ready, roads, map]);

  useEffect(() => {
    if (!ready) return;
    instance.current.easeTo({ pitch: is3D ? 55 : 0, duration: reducedMotion ? 0 : 750 });
  }, [is3D, ready, reducedMotion]);

  useEffect(() => {
    if (ready) instance.current.setLayoutProperty('nodes', 'visibility', showNodes ? 'visible' : 'none');
  }, [ready, showNodes]);

  useEffect(() => {
    if (!ready) return;
    const view = instance.current, gl = library.current;
    const features = routeFeatures(routes, selectedRoute);
    view.getSource('alternatives').setData(features);
    view.getSource('selected').setData({ type: 'FeatureCollection', features: features.features.filter((feature) => feature.properties.selected) });
    const labels = [];
    features.features.forEach((feature) => {
      const index = feature.properties.index, selected = index === selectedRoute;
      const point = linePosition(feature.geometry.coordinates, selected ? 0.52 : index % 2 ? 0.27 : 0.76);
      const wrapper = document.createElement('div');
      wrapper.style.zIndex = selected ? '2' : '1';
      const label = document.createElement('div');
      label.className = `map-route-label${selected ? ' is-selected' : ' is-alternative'}`;
      const duration = document.createElement('strong');
      duration.textContent = formatDuration(routes[index].durationSeconds);
      label.append(duration);
      if (selected) {
        const distance = document.createElement('span');
        distance.textContent = formatDistance(routes[index].distanceMeters);
        label.append(distance);
      }
      wrapper.append(label);
      labels.push(new gl.Marker({ element: wrapper, anchor: 'bottom', offset: [0, selected ? -20 : 32] }).setLngLat(point).addTo(view));
    });
    if (features.features.length) fitMap(view, features.features.flatMap((feature) => feature.geometry.coordinates), container.current, reducedMotion);
    return () => labels.forEach((label) => label.remove());
  }, [ready, routes, selectedRoute, reducedMotion]);

  const markerKey = markers.map((marker) => marker ? `${marker.longitude},${marker.latitude},${marker.name}` : '').join('|');
  useEffect(() => {
    if (!ready) return;
    const pins = markers.flatMap((place, index) => {
      if (!place) return [];
      const element = document.createElement('div'), dot = document.createElement('span');
      element.className = index === 0 ? 'map-origin-marker' : 'map-destination-marker';
      element.setAttribute('role', 'img');
      element.setAttribute('aria-label', `${index === 0 ? 'Điểm đi' : 'Điểm đến'}: ${place.name || ''}`);
      dot.className = index === 0 ? 'map-origin-dot' : 'map-destination-pin';
      element.append(dot);
      return [new library.current.Marker({ element, anchor: index === 0 ? 'center' : 'bottom' })
        .setLngLat([place.longitude, place.latitude]).addTo(instance.current)];
    });
    return () => pins.forEach((pin) => pin.remove());
  }, [ready, markerKey]);

  useEffect(() => {
    if (!ready || !active || !route?.geometry?.coordinates?.length) return;
    const view = instance.current, coordinates = route.geometry.coordinates;
    let frame, start, previousFrame = 0;
    const element = document.createElement('div');
    element.className = 'map-journey-marker';
    element.title = 'Minh hoạ hành trình, không phải vị trí trực tiếp';
    element.setAttribute('aria-hidden', 'true');
    const arrow = document.createElement('span'); element.append(arrow);
    const vehicle = new library.current.Marker({ element }).setLngLat(coordinates[0]).addTo(view);
    const draw = (progress) => {
      const gradient = (color) => ['step', ['line-progress'], color, Math.max(0.00001, progress), 'rgba(0, 0, 0, 0)'];
      view.setPaintProperty('route-line', 'line-gradient', progress >= 1 ? '#0E7C7B' : gradient('#0E7C7B'));
      view.setPaintProperty('route-outline', 'line-gradient', progress >= 1 ? '#0B5C5E' : gradient('#0B5C5E'));
      view.setPaintProperty('route-dashes', 'line-opacity', progress >= 1 ? 0.9 : 0);
    };
    if (reducedMotion) {
      draw(1);
      vehicle.remove();
      return undefined;
    }
    draw(0);
    const tick = (time) => {
      if (start === undefined) start = time;
      const elapsed = time - start;
      if (time - previousFrame >= 32) {
        previousFrame = time;
        const progress = Math.min(1, elapsed / 2400);
        if (elapsed < 2500) draw(1 - (1 - progress) ** 3);
        if (elapsed >= 2400) {
          const travel = ((elapsed - 2400) % 11000) / 11000;
          const point = linePosition(coordinates, travel), next = linePosition(coordinates, Math.min(1, travel + 0.003));
          vehicle.setLngLat(point);
          const heading = Math.atan2((next[0] - point[0]) * Math.cos(point[1] * Math.PI / 180), next[1] - point[1]) * 180 / Math.PI;
          element.style.setProperty('--vehicle-heading', `${heading - view.getBearing()}deg`);
          const dash = (elapsed % 1400) / 1400 * 8;
          view.setPaintProperty('route-dashes', 'line-dasharray', [0, dash, 2, 8 - dash]);
        }
      }
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => { cancelAnimationFrame(frame); vehicle.remove(); };
  }, [ready, route, reducedMotion, active]);

  function locate() {
    if (!navigator.geolocation) {
      const message = 'Trình duyệt này chưa hỗ trợ vị trí. Bạn có thể chọn điểm trên bản đồ.';
      setLocationError(message); onLocationError?.(message); return;
    }
    setLocating(true); setLocationError('');
    navigator.geolocation.getCurrentPosition((position) => {
      setLocating(false);
      if (!instance.current) return;
      const { longitude, latitude } = position.coords;
      const bounds = map?.bounds;
      if (bounds && (latitude < bounds.south || latitude > bounds.north || longitude < bounds.west || longitude > bounds.east)) {
        const message = 'Vị trí của bạn nằm ngoài vùng bản đồ Thanh Xuân. Hãy chọn một điểm trong khu vực này.';
        setLocationError(message); onLocationError?.(message); return;
      }
      locationMarker.current?.remove();
      const element = document.createElement('div'); element.className = 'map-user-location';
      element.setAttribute('role', 'img'); element.setAttribute('aria-label', 'Vị trí của bạn');
      locationMarker.current = new library.current.Marker({ element }).setLngLat([longitude, latitude]).addTo(instance.current);
      instance.current.easeTo({ center: [longitude, latitude], zoom: 17, duration: reducedMotion ? 0 : 900 });
    }, (error) => {
      setLocating(false);
      const message = error.code === 1 ? 'Chưa được cấp quyền vị trí. Cho phép vị trí trong trình duyệt hoặc chọn điểm trên bản đồ.'
        : 'Chưa xác định được vị trí. Vui lòng thử lại hoặc chọn điểm trên bản đồ.';
      setLocationError(message); onLocationError?.(message);
    }, { enableHighAccuracy: true, timeout: 10000, maximumAge: 30000 });
  }

  return <div className={`map-view${is3D ? ' is-3d' : ''}${route ? ' has-route' : ''}${fallback ? ' is-fallback' : ''}${navigating ? ' is-navigating' : ''}`}
    role="region" aria-label="Bản đồ Thanh Xuân">
    {fallback ? <>
      <NetworkCanvas nodes={map?.places || EMPTY} roads={map?.roads || EMPTY} routes={routes}
        selectedRoute={selectedRoute} markers={markers} mode={mode} onPick={onPick} label="Bản đồ đường phố Thanh Xuân — chế độ 2D" />
      <div className="map-fallback-note" role="status">Bản đồ 2D · Thiết bị chưa hỗ trợ hiển thị 3D</div>
    </> : <>
      <div ref={container} className="map-view-canvas" />
      {is3D && ready && <div className="map-ambient-clouds" aria-hidden="true"><span /><span /></div>}
      {!ready && <div className="map-view-loading" role="status"><span />Đang mở bản đồ…</div>}
      <div className="map-navigation" aria-label="Điều khiển bản đồ">
        <button type="button" className="map-control map-compass" title="Quay về hướng Bắc" aria-label="Quay về hướng Bắc" disabled={!ready}
          onClick={() => instance.current?.easeTo({ bearing: 0, duration: reducedMotion ? 0 : 500 })}>
          <span style={{ transform: `rotate(${-bearing}deg)` }}><Icon name="compass" size={23} /></span><small>B</small>
        </button>
        <button type="button" className={`map-control${locating ? ' is-locating' : ''}`} title="Vị trí của tôi" aria-label="Vị trí của tôi"
          disabled={!ready || locating} onClick={locate}><Icon name="locate" size={22} /></button>
        <div className="map-zoom-controls">
          <button type="button" className="map-control" title="Phóng to" aria-label="Phóng to" disabled={!ready}
            onClick={() => instance.current?.zoomIn({ duration: reducedMotion ? 0 : 300 })}><Icon name="plus" size={23} /></button>
          <button type="button" className="map-control" title="Thu nhỏ" aria-label="Thu nhỏ" disabled={!ready}
            onClick={() => instance.current?.zoomOut({ duration: reducedMotion ? 0 : 300 })}><Icon name="minus" size={23} /></button>
        </div>
      </div>
      <div className="map-attribution"><a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noreferrer">© OpenStreetMap contributors</a></div>
    </>}
    {locationError && <div className="map-location-error" role="alert">{locationError}
      <button type="button" onClick={() => setLocationError('')} aria-label="Đóng thông báo vị trí">×</button></div>}
    {showTraffic && <div className="map-traffic-note">Giao thông: dữ liệu quan sát trong bảng điều khiển</div>}
    {is3D && !fallback && <div className="map-data-note">Góc nhìn nghiêng · Chưa có dữ liệu toà nhà</div>}
  </div>;
}
