import { ALGORITHM_NAMES } from './ControlPanel.jsx';

const STATUSES = {
  SUCCESS: 'Đã tìm thấy kết quả', START_EQUALS_GOAL: 'Điểm đầu trùng điểm cuối',
  UNREACHABLE: 'Không tìm thấy đường nối hai điểm', PARTIAL: 'Rừng khung của đồ thị rời rạc',
  INVALID: 'Đầu vào không phù hợp', NEGATIVE_CYCLE: 'Có chu trình âm',
};
const EVENTS = { SETTLE: 'Chốt nút', VISIT: 'Duyệt nút', SELECT_EDGE: 'Chọn cạnh',
  RELAX: 'Cập nhật khoảng cách', INTERMEDIATE: 'Nút trung gian' };
const number = (value) => value.toLocaleString('vi-VN', { maximumFractionDigits: 4 });

export default function ResultsPanel({ result, running, traceStep = 0, comparisons = [],
  selectedAlgorithm, nodeCount = 0 }) {
  const validRuns = comparisons.filter((run) => run.status !== 'INVALID');
  const dijkstra = validRuns.find((run) => run.algorithm === 'DIJKSTRA');
  const astar = validRuns.find((run) => run.algorithm === 'ASTAR');
  const comparable = dijkstra?.status === 'SUCCESS' && astar?.status === 'SUCCESS'
    && dijkstra.metrics.visitedNodes > 0 && dijkstra.totalCost !== null && astar.totalCost !== null;
  const saving = comparable ? Math.round((1 - astar.metrics.visitedNodes / dijkstra.metrics.visitedNodes) * 100) : 0;
  const sameCost = comparable && Math.abs(dijkstra.totalCost - astar.totalCost) < 1e-6;

  return <section className="analysis-card analysis-results" aria-labelledby="results-title" aria-busy={running}>
    <div className="analysis-card-heading">
      <h2 id="results-title">Số nút đã duyệt</h2>
      <span>trên {nodeCount.toLocaleString('vi-VN')} nút</span>
    </div>
    <div aria-live="polite">
      {running && <p className="analysis-result-note">Đang tính toán trên đồ thị…</p>}
      {!validRuns.length && !running && <div className="analysis-chart-empty">
        <span className="empty-chart-bars" aria-hidden="true"><i /><i /><i /><i /></span>
        <p>Chạy một thuật toán để xem số nút đã duyệt.</p>
        <span>Chạy thêm thuật toán trên cùng hai điểm để so sánh.</span>
      </div>}
    </div>
    {validRuns.length > 0 && <div className="analysis-bars" aria-label="So sánh số nút đã duyệt">
      {validRuns.map((run) => {
        const selected = run.algorithm === selectedAlgorithm;
        return <div className={`analysis-bar-row${selected ? ' is-selected' : ''}`} key={run.algorithm}>
          <div><span>{ALGORITHM_NAMES[run.algorithm] || run.algorithm}{selected ? ' (đang chọn)' : ''}</span>
            <strong>{number(run.metrics.visitedNodes)}</strong></div>
          <div className="analysis-bar-track" role="meter" aria-label={`${ALGORITHM_NAMES[run.algorithm]}: số nút đã duyệt`}
            aria-valuenow={run.metrics.visitedNodes} aria-valuemin={0}
            aria-valuemax={Math.max(nodeCount, run.metrics.visitedNodes)}>
            <span style={{ width: `${Math.min(100, run.metrics.visitedNodes / Math.max(1, nodeCount) * 100)}%` }} />
          </div>
        </div>;
      })}
      {sameCost && saving > 0 ? <p className="analysis-insight">
        A* duyệt ít hơn Dijkstra <strong>{saving}%</strong> nút, cùng chi phí đường đi <strong>{number(astar.totalCost)} km</strong>.
      </p> : <p className="analysis-result-note">Số liệu từ các lần chạy trên cùng đồ thị và cùng điểm đầu, điểm đích.</p>}
    </div>}
    {result && <div className="analysis-result-details">
      <p className={`analysis-result-status${['INVALID', 'UNREACHABLE', 'NEGATIVE_CYCLE'].includes(result.status) ? ' is-warning' : ''}`}>
        <span aria-hidden="true">{['SUCCESS', 'START_EQUALS_GOAL'].includes(result.status) ? '✓' : '·'}</span>
        {STATUSES[result.status] || result.status}
      </p>
      <dl className="analysis-metrics">
        <div><dt>Chi phí đường đi</dt><dd>{result.totalCost == null ? '—' : number(result.totalCost)}<small> km</small></dd></div>
        <div><dt>Thời gian xử lý</dt><dd>{number(result.metrics.elapsedNanos / 1e6)}<small> ms</small></dd></div>
      </dl>
      <details className="analysis-trace-details">
        <summary>Chi tiết kết quả và các bước duyệt</summary>
        {result.message !== 'Completed.' && <p>{result.message}</p>}
        <p>{result.edges.length.toLocaleString('vi-VN')} cạnh · {result.metrics.traceStepCount.toLocaleString('vi-VN')} sự kiện</p>
        <h3>Dãy nút kết quả</h3>
        <p className="path-list">{result.nodeIds.join(' → ') || '—'}</p>
        <h3>Các bước đang xem</h3>
        {result.metrics.traceTruncated && <p className="analysis-result-note">Chỉ lưu {result.trace.length.toLocaleString('vi-VN')} bước đầu; kết quả và số nút đã duyệt vẫn đầy đủ.</p>}
        <ol className="trace-list" start={Math.max(0, traceStep - 20) + 1}>
          {result.trace.slice(Math.max(0, traceStep - 20), traceStep).map((event) => <li key={event.index}>
            <strong>{EVENTS[event.event] || event.event}</strong> {event.nodeId}
            <span>{event.cost == null ? '—' : number(event.cost)}</span>
          </li>)}
        </ol>
      </details>
    </div>}
  </section>;
}
