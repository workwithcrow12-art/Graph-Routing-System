export const EMPTY_FEATURES = { type: 'FeatureCollection', features: [] };

export function roadFeatures(data) {
  const places = new Map((data?.places || []).map((place) => [place.id, place]));
  const seen = new Set();
  const features = [];
  for (const road of data?.roads || []) {
    const shape = road.geometry?.length >= 2 ? road.geometry : [places.get(road.source), places.get(road.target)];
    const coordinates = shape.filter(Boolean).map((point) => [point.longitude, point.latitude]);
    if (coordinates.length < 2 || coordinates.some((point) => !point.every(Number.isFinite))) continue;
    const forward = JSON.stringify(coordinates), reverse = JSON.stringify([...coordinates].reverse());
    const identity = `${road.sourceWayId || road.name || ''}:${forward < reverse ? forward : reverse}`;
    if (seen.has(identity)) continue;
    seen.add(identity);
    const speed = Math.max(0, ...Object.values(road.speedsKph || {}));
    features.push({ type: 'Feature', properties: {
      name: road.name || '', width: speed >= 45 ? 3 : speed >= 25 ? 2 : 1,
    }, geometry: { type: 'LineString', coordinates } });
  }
  return { type: 'FeatureCollection', features };
}

export function routeFeatures(routes, selectedRoute) {
  return { type: 'FeatureCollection', features: routes.flatMap((route, index) => {
    if ((index > 0 && route.sameNodeSequenceAsPrimary) || !route.geometry?.coordinates?.length) return [];
    return [{ type: 'Feature', properties: { index, selected: index === selectedRoute }, geometry: route.geometry }];
  }) };
}

export function geometryBounds(coordinates) {
  if (!coordinates.length) return null;
  let west = Infinity, south = Infinity, east = -Infinity, north = -Infinity;
  for (const [lng, lat] of coordinates) {
    west = Math.min(west, lng); east = Math.max(east, lng);
    south = Math.min(south, lat); north = Math.max(north, lat);
  }
  return [[west, south], [east, north]];
}

function segmentLength(a, b) {
  const radians = Math.PI / 180;
  const latitude = (b[1] - a[1]) * radians, longitude = (b[0] - a[0]) * radians;
  const h = Math.sin(latitude / 2) ** 2 + Math.cos(a[1] * radians) * Math.cos(b[1] * radians) * Math.sin(longitude / 2) ** 2;
  return 2 * Math.atan2(Math.sqrt(h), Math.sqrt(Math.max(0, 1 - h)));
}

export function linePosition(coordinates, progress) {
  if (coordinates.length < 2) return coordinates[0];
  const lengths = coordinates.slice(1).map((point, index) => segmentLength(coordinates[index], point));
  let remaining = lengths.reduce((total, value) => total + value, 0) * Math.max(0, Math.min(1, progress));
  for (let index = 0; index < lengths.length; index++) {
    const length = lengths[index];
    if (remaining <= length && length > 0) {
      const part = remaining / length;
      return coordinates[index].map((value, axis) => value + (coordinates[index + 1][axis] - value) * part);
    }
    remaining -= length;
  }
  return coordinates.at(-1);
}
