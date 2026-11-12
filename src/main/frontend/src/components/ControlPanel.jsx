import Icon from './Icon.jsx';

export const ALGORITHM_NAMES = {
  DIJKSTRA: 'Dijkstra', ASTAR: 'A*', BFS: 'BFS', DFS: 'DFS',
  BELLMAN_FORD: 'Bellman-Ford', FLOYD_WARSHALL: 'Floyd-Warshall',
  PRIM: 'Prim', KRUSKAL: 'Kruskal',
};
export const GRAPH_NAMES = { loaded: 'OSM Thanh Xuân', sample: 'Đồ thị mẫu', custom: 'Đồ thị tự tạo' };

export default function ControlPanel({ workspace }) {
  const { graphs, algorithms, graph, selectedGraph, setSelectedGraph, form, setForm,
    loading, running, editing, run } = workspace;
  const descriptor = algorithms.find((item) => item.id === form.algorithm);
  const graphDescriptor = graphs.find((item) => item.id === selectedGraph);
  const availability = graphDescriptor?.algorithms?.find((item) => item.algorithm === form.algorithm);
  const unsupported = graphDescriptor?.algorithms?.filter((item) => !item.available) || [];
  const spanningTree = descriptor?.category === 'SPANNING_TREE';
  const busy = loading || running || editing;
  const update = (field, value) => setForm((previous) => ({ ...previous, [field]: value }));
  const options = graph?.nodes.map((node) => (
    <option key={node.id} value={node.id}>{node.name} ({node.id})</option>
  ));

  return (
    <form className="analysis-card analysis-controls" onSubmit={(event) => {
      event.preventDefault(); run();
    }}>
      <div className="analysis-card-heading">
        <h2>Thuật toán</h2>
        <span>Khám phá cách tìm đường</span>
      </div>
      <div className="algorithm-grid" role="group" aria-label="Chọn thuật toán">
        {Object.entries(ALGORITHM_NAMES).map(([id, name]) => {
          const capability = graphDescriptor?.algorithms?.find((item) => item.algorithm === id);
          const known = algorithms.some((item) => item.id === id);
          return <button key={id} type="button" data-algorithm={id}
            aria-pressed={form.algorithm === id}
            disabled={busy || !known || capability?.available === false}
            title={capability?.message || ''}
            aria-describedby={capability?.available === false ? `algorithm-reason-${id}` : undefined}
            onClick={() => update('algorithm', id)}>{name}</button>;
        })}
      </div>
      {unsupported.length > 0 && <details className="algorithm-availability">
        <summary>{unsupported.length} thuật toán cần đồ thị khác <Icon name="chevronDown" size={16} /></summary>
        <ul>{unsupported.map((item) => <li key={item.algorithm} id={`algorithm-reason-${item.algorithm}`}>
          <strong>{ALGORITHM_NAMES[item.algorithm]}: </strong>{item.message}
        </li>)}</ul>
        <p>Chọn đồ thị mẫu bên dưới để thử các thuật toán này.</p>
      </details>}
      <div className="analysis-endpoints">
        <label><span><i className="endpoint-dot" />Nút bắt đầu</span>
          <select value={form.startId} onChange={(event) => update('startId', event.target.value)}
            disabled={busy || spanningTree} required={descriptor?.requiresStart}>
            <option value="">Duyệt toàn bộ đồ thị</option>{options}
          </select>
        </label>
        <label><span><i className="endpoint-dot destination-dot" />Nút đích</span>
          <select value={form.goalId} onChange={(event) => update('goalId', event.target.value)}
            disabled={busy || spanningTree} required={descriptor?.requiresGoal}>
            <option value="">Không chọn đích</option>{options}
          </select>
        </label>
      </div>
      <button className="primary analysis-run" type="submit"
        disabled={busy || !graph || !descriptor || availability?.available === false}>
        <Icon name={running ? 'refresh' : 'play'} size={20} />
        {running ? 'Đang chạy thuật toán…' : 'Chạy thuật toán'}
      </button>
      <details className="analysis-settings">
        <summary><Icon name="graph" size={17} />{GRAPH_NAMES[selectedGraph] || selectedGraph}
          <Icon name="chevronDown" size={16} /></summary>
        <label>Đồ thị phân tích
          <select value={selectedGraph} onChange={(event) => setSelectedGraph(event.target.value)} disabled={editing}>
            {!graphs.length && <option value={selectedGraph}>Đang tải danh sách…</option>}
            {graphs.map((item) => <option key={item.id} value={item.id}>
              {GRAPH_NAMES[item.id] || item.id} · {item.nodes.toLocaleString('vi-VN')} nút
            </option>)}
          </select>
        </label>
        <label>Số bước lưu lại (tối đa 10.000)
          <input type="number" min="0" max="10000" step="1" required value={form.maxTraceSteps}
            onChange={(event) => update('maxTraceSteps', event.target.value === '' ? '' : Number(event.target.value))}
            disabled={busy} />
        </label>
        <p>BFS / DFS: bỏ cả hai nút để duyệt toàn đồ thị. Prim / Kruskal tìm cây khung, không dùng điểm đầu và đích.</p>
      </details>
    </form>
  );
}
