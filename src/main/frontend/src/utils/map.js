export function mapProjection(nodes, width = 1000, height = 650, padding = 35) {
  let south = 90, north = -90, west = 180, east = -180;
  for (const node of nodes) {
    south = Math.min(south, node.latitude); north = Math.max(north, node.latitude);
    west = Math.min(west, node.longitude); east = Math.max(east, node.longitude);
  }
  if (!nodes.length) { south = north = west = east = 0; }
  const cosine = Math.max(1e-6, Math.cos((south + north) * Math.PI / 360));
  const scale = Math.min((width - padding * 2) / Math.max((east - west) * cosine, 1e-8),
    (height - padding * 2) / Math.max(north - south, 1e-8));
  const left = (width - (east - west) * cosine * scale) / 2;
  const top = (height - (north - south) * scale) / 2;
  return {
    point: (latitude, longitude) => [left + (longitude - west) * cosine * scale, top + (north - latitude) * scale],
    coordinate: (x, y) => ({ lat: north - (y - top) / scale, lng: west + (x - left) / (cosine * scale) }),
  };
}

export function trafficBody(roads, segmentId, mode, speedKph, closed, minutes = 10, now = new Date()) {
  const road = roads.find((item) => item.id === segmentId);
  if (!road || !road.speedsKph?.[mode.toUpperCase()]) throw new Error('Chọn cung đường hỗ trợ phương tiện này.');
  const speed = Number(speedKph);
  if (!closed && (!Number.isFinite(speed) || speed <= 0 || speed > 200)) throw new Error('Tốc độ phải trong (0, 200] km/h.');
  return {
    source: 'Local UI observation', observedAt: now.toISOString(),
    expiresAt: new Date(now.getTime() + minutes * 60_000).toISOString(),
    updates: [{ from: road.source, to: road.target, sourceWayId: road.sourceWayId,
      segmentId: road.id, mode, speedKph: closed ? null : speed, closed }],
  };
}

export function filterRoads(roads, query, selectedId, limit = 100) {
  const normalize = (value) => String(value).normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase().replaceAll('đ', 'd');
  const term = normalize(query.trim());
  const matches = roads.filter((road) => normalize(`${road.name} ${road.id} ${road.source} ${road.target}`).includes(term));
  const visible = matches.slice(0, limit);
  const selected = roads.find((road) => road.id === selectedId);
  if (selected && !visible.includes(selected)) visible.unshift(selected);
  return { visible, total: matches.length };
}

export function directionText(direction, destinationName = '') {
  if (direction.maneuver === 'ARRIVE') return destinationName ? `Đã đến ${destinationName}` : 'Đến nơi';
  const prefix = { DEPART: 'Xuất phát trên', TURN_LEFT: 'Rẽ trái vào', TURN_RIGHT: 'Rẽ phải vào',
    U_TURN: 'Quay đầu vào', CONTINUE: 'Tiếp tục trên' }[direction.maneuver] || 'Tiếp tục trên';
  return `${prefix} ${direction.roadName?.trim() || 'đường không tên'}`;
}
