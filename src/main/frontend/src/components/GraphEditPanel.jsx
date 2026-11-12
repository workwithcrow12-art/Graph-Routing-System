import { useEffect, useState } from "react";
import { visibleEdges } from "../utils/graph.js";

export default function GraphEditPanel({ graph, onEdit, busy }) {
  const [node, setNode] = useState({
    id: "",
    name: "",
    latitude: "21",
    longitude: "105.8",
  });
  const [edge, setEdge] = useState({
    source: "",
    target: "",
    weight: "1",
    roadName: "",
  });
  const [removeNode, setRemoveNode] = useState("");
  const [removeEdge, setRemoveEdge] = useState("");
  const edges = visibleEdges(graph.edges, graph.graphType === "UNDIRECTED");
  const options = graph.nodes.map((item) => (
    <option key={item.id} value={item.id}>
      {item.name} ({item.id})
    </option>
  ));
  const setNodeField = (field, value) =>
    setNode((previous) => ({ ...previous, [field]: value }));
  const setEdgeField = (field, value) =>
    setEdge((previous) => ({ ...previous, [field]: value }));

  useEffect(() => {
    setRemoveEdge("");
    setRemoveNode((previous) =>
      graph.nodes.some((item) => item.id === previous) ? previous : "",
    );
    setEdge((previous) => ({
      ...previous,
      source: graph.nodes.some((item) => item.id === previous.source)
        ? previous.source
        : "",
      target: graph.nodes.some((item) => item.id === previous.target)
        ? previous.target
        : "",
    }));
  }, [graph]);

  return (
    <section className="panel graph-edit" aria-labelledby="edit-title">
      <h2 id="edit-title">Chỉnh graph custom</h2>
      <p className="hint">
        Thay đổi chỉ ở graph phân tích trong RAM, không sửa mạng OSM dùng để
        routing.
      </p>
      <fieldset disabled={busy}>
        <legend>Thêm nút</legend>
        <form
          onSubmit={async (event) => {
            event.preventDefault();
            if (
              await onEdit({
                operation: "addNode",
                node: {
                  ...node,
                  latitude: Number(node.latitude),
                  longitude: Number(node.longitude),
                },
              })
            )
              setNode((previous) => ({ ...previous, id: "", name: "" }));
          }}
        >
          <label>
            ID nút
            <input
              required
              value={node.id}
              onChange={(event) => setNodeField("id", event.target.value)}
            />
          </label>
          <label>
            Tên nút
            <input
              required
              value={node.name}
              onChange={(event) => setNodeField("name", event.target.value)}
            />
          </label>
          <label>
            Vĩ độ
            <input
              type="number"
              min="-90"
              max="90"
              step="any"
              required
              value={node.latitude}
              onChange={(event) => setNodeField("latitude", event.target.value)}
            />
          </label>
          <label>
            Kinh độ
            <input
              type="number"
              min="-180"
              max="180"
              step="any"
              required
              value={node.longitude}
              onChange={(event) =>
                setNodeField("longitude", event.target.value)
              }
            />
          </label>
          <button type="submit">Thêm nút</button>
        </form>
      </fieldset>
      <fieldset disabled={busy}>
        <legend>Thêm cạnh</legend>
        <form
          onSubmit={(event) => {
            event.preventDefault();
            onEdit({
              operation: "addEdge",
              edge: { ...edge, weight: Number(edge.weight) },
            });
          }}
        >
          <label>
            Từ nút
            <select
              required
              value={edge.source}
              onChange={(event) => setEdgeField("source", event.target.value)}
            >
              <option value="">Chọn nút</option>
              {options}
            </select>
          </label>
          <label>
            Đến nút
            <select
              required
              value={edge.target}
              onChange={(event) => setEdgeField("target", event.target.value)}
            >
              <option value="">Chọn nút</option>
              {options}
            </select>
          </label>
          <label>
            Trọng số (km)
            <input
              type="number"
              min="0"
              step="any"
              required
              value={edge.weight}
              onChange={(event) => setEdgeField("weight", event.target.value)}
            />
          </label>
          <label>
            Tên đường
            <input
              value={edge.roadName}
              onChange={(event) => setEdgeField("roadName", event.target.value)}
            />
          </label>
          <button type="submit">Thêm cạnh</button>
        </form>
      </fieldset>
      <fieldset disabled={busy}>
        <legend>Xóa phần tử</legend>
        <form
          onSubmit={async (event) => {
            event.preventDefault();
            if (await onEdit({ operation: "removeNode", nodeId: removeNode }))
              setRemoveNode("");
          }}
        >
          <label>
            Nút cần xóa
            <select
              required
              value={removeNode}
              onChange={(event) => setRemoveNode(event.target.value)}
            >
              <option value="">Chọn nút</option>
              {options}
            </select>
          </label>
          <button
            type="submit"
            disabled={!graph.nodes.some((item) => item.id === removeNode)}
          >
            Xóa nút
          </button>
        </form>
        <form
          onSubmit={async (event) => {
            event.preventDefault();
            const selected = edges[Number(removeEdge)];
            if (
              selected &&
              (await onEdit({ operation: "removeEdge", edge: selected }))
            )
              setRemoveEdge("");
          }}
        >
          <label>
            Cạnh cần xóa
            <select
              required
              value={removeEdge}
              onChange={(event) => setRemoveEdge(event.target.value)}
            >
              <option value="">Chọn cạnh</option>
              {edges.map((item, index) => (
                <option key={index} value={index}>
                  {item.source} → {item.target} · {item.weight} km ·{" "}
                  {item.roadName || "Không tên"}
                </option>
              ))}
            </select>
          </label>
          <button
            type="submit"
            disabled={removeEdge === "" || !edges[Number(removeEdge)]}
          >
            Xóa một cạnh
          </button>
        </form>
      </fieldset>
      <button
        type="button"
        disabled={busy}
        onClick={() => onEdit({ operation: "reset" })}
      >
        Khôi phục graph mẫu
      </button>
    </section>
  );
}
