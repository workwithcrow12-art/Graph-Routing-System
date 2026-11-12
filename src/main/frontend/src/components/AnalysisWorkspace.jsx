import { useEffect, useMemo, useRef, useState } from 'react';
import { useGraphWorkspace } from '../hooks/useGraphWorkspace.js';
import ControlPanel, { ALGORITHM_NAMES, GRAPH_NAMES } from './ControlPanel.jsx';
import GraphCanvas from './GraphCanvas.jsx';
import GraphEditPanel from './GraphEditPanel.jsx';
import ResultsPanel from './ResultsPanel.jsx';
import Icon, { BrandMark } from './Icon.jsx';
import '../styles/analysis.css';

const EMPTY_TRACE = [];

export default function AnalysisWorkspace({ onBack, endpoints, active = true }) {
  const workspace = useGraphWorkspace();
  const trace = workspace.result?.trace || EMPTY_TRACE;
  const [step, setStep] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [speed, setSpeed] = useState(1);
  const [reducedMotion, setReducedMotion] = useState(() =>
    window.matchMedia?.('(prefers-reduced-motion: reduce)').matches || false);
  const [showLayers, setShowLayers] = useState(false);
  const [showVisited, setShowVisited] = useState(true);
  const [showResult, setShowResult] = useState(true);
  const endpointSync = useRef('');

  useEffect(() => {
    const preference = window.matchMedia?.('(prefers-reduced-motion: reduce)');
    if (!preference) return;
    const update = () => setReducedMotion(preference.matches);
    preference.addEventListener('change', update);
    return () => preference.removeEventListener('change', update);
  }, []);

  useEffect(() => {
    if (!active) {
      setPlaying(false);
      endpointSync.current = '';
      return;
    }
    if (!workspace.graph || workspace.selectedGraph !== 'loaded') return;
    const origin = endpoints?.origin?.id;
    const destination = endpoints?.destination?.id;
    const key = JSON.stringify([origin, destination, workspace.graph.revision]);
    if (endpointSync.current === key) return;
    endpointSync.current = key;
    if (!origin && !destination) return;
    const ids = new Set(workspace.graph.nodes.map((node) => node.id));
    workspace.setForm((form) => ({ ...form,
      startId: ids.has(origin) ? origin : form.startId,
      goalId: ids.has(destination) ? destination : form.goalId,
    }));
  }, [active, endpoints?.origin?.id, endpoints?.destination?.id, workspace.graph,
    workspace.selectedGraph, workspace.setForm]);

  useEffect(() => {
    setStep(reducedMotion ? trace.length : 0);
    setPlaying(false);
  }, [trace, reducedMotion]);

  useEffect(() => {
    if (!playing || reducedMotion || !active || step >= trace.length) return;
    const timer = window.setTimeout(() => {
      setStep((current) => Math.min(trace.length, current + Math.max(1, Math.ceil(trace.length / 160))));
    }, 50 / speed);
    return () => window.clearTimeout(timer);
  }, [playing, reducedMotion, active, step, trace.length, speed]);

  const visibleTrace = useMemo(() => showVisited ? trace : EMPTY_TRACE, [trace, showVisited]);
  const comparisonRun = workspace.form.algorithm === 'ASTAR'
    ? workspace.comparisons.find((run) => run.algorithm === 'DIJKSTRA') : null;
  const comparisonVisited = useMemo(() => new Set(showVisited
    ? (comparisonRun?.trace || EMPTY_TRACE).map((event) => event.nodeId) : []), [comparisonRun, showVisited]);
  const ended = step >= trace.length;
  const busy = workspace.loading || workspace.running || workspace.editing;
  const nodeCount = workspace.graph?.nodes.length || 0;
  const algorithmName = ALGORITHM_NAMES[workspace.form.algorithm] || workspace.form.algorithm;

  function moveStep(next) {
    setPlaying(false);
    setStep(Math.max(0, Math.min(trace.length, next)));
  }

  function togglePlayback() {
    if (reducedMotion) return;
    if (ended) { setStep(0); setPlaying(true); }
    else setPlaying((value) => !value);
  }

  return <main id="analysis-content" tabIndex={-1} className="analysis-workspace" aria-label="Phân tích thuật toán">
    <section className="analysis-map" aria-label="Đồ thị trực quan">
      {workspace.loading ? <div className="analysis-map-state" role="status">
        <span className="analysis-loading-mark"><Icon name="graph" size={34} /></span>
        <strong>Đang mở đồ thị…</strong><span>Kết nối những con đường Thanh Xuân</span>
      </div> : workspace.graph && !nodeCount ? <div className="analysis-map-state">
        <Icon name="graph" size={34} /><strong>Đồ thị chưa có nút</strong>
        <span>Thêm nút trong bảng chỉnh sửa để bắt đầu tạo đồ thị.</span>
      </div> : workspace.graph ? <GraphCanvas graph={workspace.graph}
        result={showResult && ended ? workspace.result : null}
        trace={visibleTrace} step={showVisited ? step : 0} comparisonVisited={comparisonVisited} /> : <div className="analysis-map-state">
        <Icon name="graph" size={34} /><strong>Chưa tải được đồ thị</strong>
        <span>Kiểm tra kết nối và thử tải lại dữ liệu.</span>
        <button type="button" onClick={workspace.reload}><Icon name="refresh" />Thử lại</button>
      </div>}
    </section>

    <div className="analysis-topbar">
      <header className="analysis-title-card">
        <button type="button" className="icon-button" aria-label="Trở về bản đồ" onClick={onBack}><Icon name="arrowLeft" /></button>
        <BrandMark className="analysis-brand-mark" size={28} />
        <h1>Phân tích thuật toán</h1>
      </header>
      <div className="analysis-dataset-chip"><Icon name="graph" size={18} />
        <span>{GRAPH_NAMES[workspace.selectedGraph] || workspace.selectedGraph}
          {workspace.graph && <> · {nodeCount.toLocaleString('vi-VN')} nút <span className="analysis-arc-count">· {workspace.graph.edges.length.toLocaleString('vi-VN')} cung</span></>}
        </span>
      </div>
      <div className="analysis-top-actions">
        <button type="button" className="icon-button" aria-label="Tải lại dữ liệu phân tích" title="Tải lại dữ liệu" disabled={busy} onClick={workspace.reload}><Icon name="refresh" /></button>
        <button type="button" className="analysis-layer-button" aria-expanded={showLayers} onClick={() => setShowLayers((value) => !value)}><Icon name="layers" size={18} /><span>Lớp bản đồ</span></button>
        {showLayers && <section className="analysis-layers analysis-card" aria-label="Lớp hiển thị">
          <h2>Lớp hiển thị</h2>
          <label><input type="checkbox" checked={showVisited} onChange={(event) => setShowVisited(event.target.checked)} />Nút đã duyệt</label>
          <label><input type="checkbox" checked={showResult} onChange={(event) => setShowResult(event.target.checked)} />Đường / cây kết quả</label>
          <p>Mạng đường lấy từ OpenStreetMap.</p>
        </section>}
      </div>
    </div>

    <aside className="analysis-sidebar" aria-label="Thiết lập và so sánh thuật toán">
      {workspace.error && <div className="analysis-error" role="alert"><strong>Chưa thể hoàn tất</strong>
        <p>{workspace.error}</p><button type="button" onClick={workspace.reload} disabled={busy}><Icon name="refresh" size={16} />Thử lại</button>
      </div>}
      <ControlPanel workspace={workspace} />
      <ResultsPanel result={workspace.result} running={workspace.running} traceStep={step}
        comparisons={workspace.comparisons} selectedAlgorithm={workspace.form.algorithm} nodeCount={nodeCount} />
      {workspace.selectedGraph === 'custom' && workspace.graph && <GraphEditPanel
        graph={workspace.graph} onEdit={workspace.edit} busy={workspace.editing || workspace.running} />}
    </aside>

    <section className="analysis-timeline analysis-card" aria-label="Phát lại các bước thuật toán">
      <div className="analysis-playback-row">
        <div className="analysis-playback-buttons">
          <button type="button" className="icon-button" aria-label="Bước trước" disabled={!trace.length || step === 0} onClick={() => moveStep(step - 1)}><Icon name="skipBack" /></button>
          <button type="button" className="analysis-play-button" aria-label={playing && !ended ? 'Tạm dừng' : ended ? 'Phát lại' : 'Phát các bước'}
            disabled={!trace.length || reducedMotion} onClick={togglePlayback}><Icon name={playing && !ended ? 'pause' : 'play'} size={23} /></button>
          <button type="button" className="icon-button" aria-label="Bước sau" disabled={!trace.length || ended} onClick={() => moveStep(step + 1)}><Icon name="skipForward" /></button>
        </div>
        <div className="analysis-progress">
          <input aria-label="Bước thuật toán" type="range" min="0" max={trace.length || 1} value={step}
            disabled={!trace.length} onChange={(event) => moveStep(Number(event.target.value))} />
          <div><span>Bước {step.toLocaleString('vi-VN')} / {trace.length.toLocaleString('vi-VN')}</span>
            <span>{workspace.result ? ended ? `${workspace.result.edges.length.toLocaleString('vi-VN')} cạnh kết quả` : `${algorithmName} đang duyệt` : 'Sẵn sàng khám phá'}</span></div>
        </div>
        <div className="analysis-speed" role="group" aria-label="Tốc độ phát lại">
          {[0.5, 1, 2, 4].map((value) => <button key={value} type="button" aria-pressed={speed === value}
            onClick={() => setSpeed(value)} disabled={reducedMotion}>{value.toLocaleString('vi-VN')}×</button>)}
        </div>
      </div>
      <div className="analysis-timeline-footer">
        <div className="analysis-legend"><span><i />Chưa duyệt</span>
          {comparisonVisited.size > 0 && <span title="Các nút đã duyệt trong lần chạy Dijkstra cùng điểm đầu và điểm cuối"><i className="comparison" />Dijkstra</span>}
          <span><i className="visited" />{comparisonVisited.size > 0 ? 'A*' : 'Đã duyệt'}</span><span><i className="current" />Bước hiện tại</span><span><i className="path" />Kết quả</span></div>
        {reducedMotion ? <span>Giảm chuyển động · dùng từng bước</span> : workspace.result?.metrics.traceTruncated ? <span>Chỉ phát lại {trace.length.toLocaleString('vi-VN')} bước đã lưu</span> : <span>{algorithmName} · Dữ liệu thực từ backend</span>}
      </div>
    </section>
    <nav className="analysis-mobile-nav" aria-label="Điều hướng chính">
      <button type="button" onClick={onBack}><Icon name="map" size={20} /><span>Bản đồ</span></button>
      <button type="button" onClick={onBack}><Icon name="arrowLeft" size={20} /><span>Chỉ đường</span></button>
      <button type="button" aria-current="page"><Icon name="graph" size={20} /><span>Thuật toán</span></button>
    </nav>
  </main>;
}
