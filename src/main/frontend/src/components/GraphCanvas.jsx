import { useId, useMemo } from "react";
import { pathEdgeIndexes, projectNodes, visibleEdges } from "../utils/graph.js";
import NetworkCanvas from './NetworkCanvas.jsx';

const WIDTH = 960,
  HEIGHT = 640,
  PADDING = 72,
  MAX_NODES = 300;

function NodeView({ node, visited, comparisonVisited, current, onPath }) {
  const state = current
    ? "current"
    : onPath
      ? "path"
      : visited
        ? "visited"
        : comparisonVisited
          ? "comparison"
          : "normal";
  return (
    <g className={`node node--${state}`}>
      <title>
        {node.name} ({node.id})
      </title>
      <circle cx={node.x} cy={node.y} r="9" />
      <text x={node.x} y={node.y - 16} textAnchor="middle">
        {node.name.length > 27 ? `${node.name.slice(0, 26)}…` : node.name}
      </text>
    </g>
  );
}

function EdgeView({ edge, from, to, directed, onPath, offset, markerId }) {
  const dx = to.x - from.x,
    dy = to.y - from.y,
    length = Math.hypot(dx, dy) || 1;
  const midX = (from.x + to.x) / 2 - (dy / length) * offset;
  const midY = (from.y + to.y) / 2 + (dx / length) * offset;
  const path =
    from.id === to.id
      ? `M ${from.x} ${from.y} C ${from.x - 55} ${from.y - 70}, ${from.x + 55} ${from.y - 70}, ${from.x} ${from.y}`
      : `M ${from.x} ${from.y} Q ${midX} ${midY}, ${to.x} ${to.y}`;
  return (
    <g className={`edge${onPath ? " edge--path" : ""}`}>
      <title>
        {edge.roadName || "Cạnh"} · {edge.weight} km
      </title>
      <path
        d={path}
        fill="none"
        markerEnd={directed ? `url(#${markerId})` : undefined}
      />
      <text x={midX} y={midY - 5} textAnchor="middle">
        {Number(edge.weight.toFixed(3))}
      </text>
    </g>
  );
}

export default function GraphCanvas({ graph, result, trace, step, comparisonVisited = new Set() }) {
  const markerId = `arrow-${useId().replaceAll(":", "")}`;
  const nodes = useMemo(
    () =>
      graph && graph.nodes.length <= MAX_NODES
        ? projectNodes(graph.nodes, WIDTH, HEIGHT, PADDING)
        : [],
    [graph],
  );
  const positions = useMemo(
    () => new Map(nodes.map((node) => [node.id, node])),
    [nodes],
  );
  const undirected = graph?.graphType === "UNDIRECTED";
  const edges = useMemo(
    () => visibleEdges(graph?.edges || [], undirected),
    [graph, undirected],
  );
  const visited = new Set(trace.slice(0, step).map((event) => event.nodeId));
  const current = step > 0 ? trace[step - 1]?.nodeId : null;
  const complete = !!result && step >= trace.length;
  const pathEdges = pathEdgeIndexes(edges, complete ? result.edges : [], undirected);
  const pathNodes = new Set(
    complete
      ? result.edges.flatMap((edge) => [edge.sourceId, edge.destinationId])
      : [],
  );
  if (complete && result.status === "START_EQUALS_GOAL")
    result.nodeIds.forEach((id) => pathNodes.add(id));
  const pairs = new Map(),
    offsets = new Map();
  edges.forEach((edge, index) => {
    const pair = JSON.stringify([edge.source, edge.target].sort());
    if (!pairs.has(pair)) pairs.set(pair, []);
    pairs.get(pair).push(index);
  });
  pairs.forEach((indexes) =>
    indexes.forEach((index, position) =>
      offsets.set(index, (position - (indexes.length - 1) / 2) * 22),
    ),
  );

  if (!graph) return <p>Chưa có dữ liệu. Hãy chạy backend rồi bấm tải lại.</p>;
  if (graph.nodes.length > MAX_NODES)
    return (
      <NetworkCanvas nodes={graph.nodes} roads={edges} visited={visited} comparisonVisited={comparisonVisited} current={current}
        highlighted={pathEdges} selectedNodes={pathNodes} label={`Đồ thị phân tích ${graph.nodes.length} nút`} />
    );
  if (!nodes.length) return <p>Graph rỗng. Thêm nút ở bảng chỉnh sửa.</p>;

  return (
    <>
      <svg
        className="graph-canvas"
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        role="img"
        aria-label={`Đồ thị gồm ${nodes.length} nút và ${edges.length} cạnh, trọng số theo km`}
      >
        <defs>
          <marker
            id={markerId}
            viewBox="0 0 10 10"
            refX="21"
            refY="5"
            markerWidth="6"
            markerHeight="6"
            orient="auto"
          >
            <path d="M 0 0 L 10 5 L 0 10 z" fill="context-stroke" />
          </marker>
        </defs>
        {edges.map(
          (edge, index) =>
            positions.has(edge.source) &&
            positions.has(edge.target) && (
              <EdgeView
                key={index}
                edge={edge}
                from={positions.get(edge.source)}
                to={positions.get(edge.target)}
                directed={!undirected}
                onPath={pathEdges.has(index)}
                offset={
                  offsets.get(index) * (edge.source > edge.target ? -1 : 1)
                }
                markerId={markerId}
              />
            ),
        )}
        {nodes.map((node) => (
          <NodeView
            key={node.id}
            node={node}
            visited={visited.has(node.id)}
            comparisonVisited={comparisonVisited.has(node.id)}
            current={current === node.id}
            onPath={pathNodes.has(node.id)}
          />
        ))}
      </svg>
      <p className="legend">
        <span>Chưa xét</span>
        <span className="legend-visited">Đã xét</span>
        <span className="legend-current">Bước hiện tại</span>
        <span className="legend-path">Kết quả</span>
      </p>
    </>
  );
}
