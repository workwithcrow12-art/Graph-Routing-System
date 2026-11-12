export function projectNodes(nodes, width, height, padding = 40) {
  if (!nodes.length) return [];
  if (width <= 2 * padding || height <= 2 * padding) {
    throw new RangeError("Canvas phải lớn hơn phần padding.");
  }
  const latitudes = nodes.map((node) => node.latitude);
  const longitudes = nodes.map((node) => node.longitude);
  const minLat = Math.min(...latitudes),
    maxLat = Math.max(...latitudes);
  const minLng = Math.min(...longitudes),
    maxLng = Math.max(...longitudes);
  const longitudeScale = Math.max(
    1e-6,
    Math.cos((((minLat + maxLat) / 2) * Math.PI) / 180),
  );
  const spanX = (maxLng - minLng) * longitudeScale,
    spanY = maxLat - minLat;
  const scales = [];
  if (spanX > 0) scales.push((width - 2 * padding) / spanX);
  if (spanY > 0) scales.push((height - 2 * padding) / spanY);
  const scale = scales.length ? Math.min(...scales) : 1;
  const left = (width - spanX * scale) / 2,
    top = (height - spanY * scale) / 2;
  return nodes.map((node) => ({
    ...node,
    x: left + (node.longitude - minLng) * longitudeScale * scale,
    y: top + (maxLat - node.latitude) * scale,
  }));
}

export function edgeKey(edge, undirected = false) {
  let from = edge.sourceId ?? edge.source,
    to = edge.destinationId ?? edge.target;
  if (undirected && from > to) [from, to] = [to, from];
  return JSON.stringify([from, to, edge.weight, edge.roadName || ""]);
}

// Match the result's multiplicity, so identical parallel roads highlight only
// as many visible copies as the algorithm actually selected.
export function pathEdgeIndexes(edges, resultEdges, undirected = false) {
  const remaining = new Map();
  for (const edge of resultEdges) {
    const key = edgeKey(edge, undirected);
    remaining.set(key, (remaining.get(key) || 0) + 1);
  }
  const selected = new Set();
  edges.forEach((edge, index) => {
    const key = edgeKey(edge, undirected), count = remaining.get(key) || 0;
    if (count > 0) {
      selected.add(index);
      remaining.set(key, count - 1);
    }
  });
  return selected;
}

// The API stores two arcs per undirected edge. Preserve parallel edges.
export function visibleEdges(edges, undirected) {
  if (!undirected) return edges;
  const loops = new Map();
  return edges.filter((edge) => {
    if (edge.source !== edge.target) return edge.source < edge.target;
    const key = edgeKey(edge),
      count = loops.get(key) || 0;
    loops.set(key, count + 1);
    return count % 2 === 0;
  });
}
