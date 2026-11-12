import { useEffect, useMemo, useRef, useState } from 'react';
import { mapProjection } from '../utils/map.js';

export default function NetworkCanvas({ nodes, roads, routes = [], selectedRoute = 0, markers = [],
  mode, visited = new Set(), comparisonVisited = new Set(), current, highlighted = new Set(), selectedNodes = new Set(), onPick, label = 'Bản đồ mạng đường OSM' }) {
  const canvas = useRef(null), drag = useRef(null);
  const [{ width, height }, setSize] = useState({ width: 1000, height: 650 });
  const [view, setView] = useState({ zoom: 1, x: 0, y: 0 });
  const projection = useMemo(() => mapProjection([...nodes, ...roads.flatMap((road) =>
    (road.geometry || []).map((point) => ({ latitude: point.latitude, longitude: point.longitude })))], width, height), [nodes, roads, width, height]);
  const positions = useMemo(() => new Map(nodes.map((node) => [node.id,
    projection.point(node.latitude, node.longitude)])), [nodes, projection]);
  useEffect(() => { setView({ zoom: 1, x: 0, y: 0 }); }, [nodes, width, height]);

  useEffect(() => {
    const element = canvas.current;
    function measure() {
      const rect = element.getBoundingClientRect();
      if (rect.width <= 0 || rect.height <= 0) return;
      const next = { width: Math.round(rect.width), height: Math.round(rect.height) };
      setSize((previous) => previous.width === next.width && previous.height === next.height ? previous : next);
    }
    measure();
    const observer = window.ResizeObserver ? new window.ResizeObserver(measure) : null;
    observer?.observe(element);
    window.addEventListener('resize', measure);
    return () => { observer?.disconnect(); window.removeEventListener('resize', measure); };
  }, []);

  useEffect(() => {
    const element = canvas.current;
    const ratio = Math.min(window.devicePixelRatio || 1, 2);
    element.width = width * ratio; element.height = height * ratio;
    const ctx = element.getContext('2d');
    ctx.scale(ratio, ratio);
    ctx.fillStyle = '#F4E8C9'; ctx.fillRect(0, 0, width, height);
    ctx.save(); ctx.translate(view.x, view.y); ctx.scale(view.zoom, view.zoom);
    function line(points, color, thickness) {
      if (!points.length) return;
      ctx.beginPath(); ctx.moveTo(...points[0]);
      for (let i = 1; i < points.length; i++) ctx.lineTo(...points[i]);
      ctx.strokeStyle = color; ctx.lineWidth = thickness / view.zoom; ctx.stroke();
    }
    roads.forEach((road, index) => {
      if (mode && road.speedsKph && !road.speedsKph[mode.toUpperCase()]) return;
      const geometry = road.geometry?.map((point) => projection.point(point.latitude, point.longitude))
        || [positions.get(road.source), positions.get(road.target)].filter(Boolean);
      line(geometry, highlighted.has(index) ? '#0B5C5E' : '#d1bd87', highlighted.has(index) ? 4 : 1);
    });
    nodes.forEach((node) => {
      const point = positions.get(node.id);
      ctx.beginPath(); ctx.arc(...point, (current === node.id ? 4 : 1.5) / view.zoom, 0, Math.PI * 2);
      ctx.fillStyle = current === node.id ? '#B4410E' : selectedNodes.has(node.id) ? '#0E7C7B'
        : visited.has(node.id) ? '#0B5C5E' : comparisonVisited.has(node.id) ? '#7db8ad' : '#b2c5b2'; ctx.fill();
    });
    routes.forEach((route, index) => {
      if (index !== selectedRoute) line(route.geometry.coordinates.map(([lng, lat]) => projection.point(lat, lng)), '#9eada4', 3);
    });
    if (routes[selectedRoute]) line(routes[selectedRoute].geometry.coordinates.map(([lng, lat]) => projection.point(lat, lng)), '#0E7C7B', 5);
    markers.forEach((marker, index) => {
      if (!marker) return;
      const point = projection.point(marker.latitude, marker.longitude);
      ctx.beginPath(); ctx.arc(...point, 7 / view.zoom, 0, Math.PI * 2);
      ctx.fillStyle = index === 0 ? '#0E7C7B' : '#B4410E'; ctx.fill();
      ctx.font = `${14 / view.zoom}px system-ui`; ctx.fillText(index === 0 ? 'A' : 'B', point[0] + 10 / view.zoom, point[1]);
    });
    ctx.restore();
  }, [nodes, roads, routes, selectedRoute, markers, mode, visited, comparisonVisited, current, highlighted, selectedNodes, projection, positions, view, width, height]);

  function zoom(factor) {
    setView((old) => {
      const next = Math.max(1, Math.min(16, old.zoom * factor)), ratio = next / old.zoom;
      return { zoom: next, x: width / 2 - (width / 2 - old.x) * ratio,
        y: height / 2 - (height / 2 - old.y) * ratio };
    });
  }
  function point(event) {
    const rect = canvas.current.getBoundingClientRect();
    return { x: (event.clientX - rect.left) * width / rect.width, y: (event.clientY - rect.top) * height / rect.height };
  }
  return <div className="network-map">
    <div className="map-tools" aria-label="Điều khiển bản đồ">
      <button type="button" onClick={() => zoom(1.5)} aria-label="Phóng to">+</button>
      <button type="button" onClick={() => zoom(1 / 1.5)} aria-label="Thu nhỏ">−</button>
      <button type="button" onClick={() => setView({ zoom: 1, x: 0, y: 0 })}>Toàn mạng</button>
      <span>{nodes.length} nút · {roads.length} cung · {view.zoom.toFixed(1)}×</span>
    </div>
    <canvas ref={canvas} className="network-canvas" role="img" aria-label={label}
      onPointerDown={(event) => { if (event.button !== 0) return; drag.current = { ...point(event), view }; event.currentTarget.setPointerCapture(event.pointerId); }}
      onPointerMove={(event) => { if (!drag.current) return; const position = point(event);
        setView({ ...drag.current.view, x: drag.current.view.x + position.x - drag.current.x,
          y: drag.current.view.y + position.y - drag.current.y }); }}
      onPointerCancel={() => { drag.current = null; }}
      onPointerUp={(event) => { if (!drag.current) return; const position = point(event), start = drag.current; drag.current = null;
        if (Math.hypot(position.x - start.x, position.y - start.y) < 5 && onPick)
          onPick(projection.coordinate((position.x - start.view.x) / start.view.zoom, (position.y - start.view.y) / start.view.zoom));
      }} />
    <p className="hint">Kéo để di chuyển, dùng +/− để phóng to. Mạng © OpenStreetMap contributors · <a href="https://www.openstreetmap.org/copyright">ODbL</a>. Không có bản đồ nền trực tuyến.</p>
  </div>;
}

