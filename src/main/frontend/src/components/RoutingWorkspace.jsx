import { useEffect, useRef, useState } from 'react';
import { api } from '../api.js';
import { directionText, filterRoads, trafficBody } from '../utils/map.js';
import { formatDistance, formatDuration, mergeDirections, visibleRoutes } from '../utils/route.js';
import { loadMap } from '../utils/mapCache.js';
import MapView from './MapView.jsx';
import PlaceSearch from './PlaceSearch.jsx';
import Icon, { BrandMark } from './Icon.jsx';

const MODES = { driving: ['Ô tô', 'car'], motorcycling: ['Xe máy', 'motorcycle'], cycling: ['Xe đạp', 'bike'], walking: ['Đi bộ', 'walk'] };
const MANEUVERS = { DEPART: 'straight', CONTINUE: 'straight', TURN_LEFT: 'left', TURN_RIGHT: 'right', U_TURN: 'swap', ARRIVE: 'pin' };

function routeSummaryRoad(route) {
  return route?.directions?.find((step) => step.roadName && step.distanceMeters > 300)?.roadName;
}

export default function RoutingWorkspace({ onAnalysis, onEndpointsChange, active = true }) {
  const [map, setMap] = useState(null), [traffic, setTraffic] = useState(null), [error, setError] = useState('');
  const [loading, setLoading] = useState(true), [busy, setBusy] = useState(false), [mode, setMode] = useState('motorcycling');
  const [origin, setOrigin] = useState(null), [destination, setDestination] = useState(null);
  const [preference, setPreference] = useState('fastest'), [avoid, setAvoid] = useState('');
  const [plan, setPlan] = useState(null), [selected, setSelected] = useState(0), [quota, setQuota] = useState(false);
  const [pick, setPick] = useState('origin'), [coordinate, setCoordinate] = useState({ lat: '20.99351', lng: '105.80436' });
  const [segment, setSegment] = useState(''), [speed, setSpeed] = useState(10), [closed, setClosed] = useState(false);
  const [trafficMessage, setTrafficMessage] = useState(''), [roadQuery, setRoadQuery] = useState('');
  const [trafficSaving, setTrafficSaving] = useState(false);
  const [is3D, setIs3D] = useState(true), [showNodes, setShowNodes] = useState(false);
  const [popover, setPopover] = useState(''), [sheet, setSheet] = useState('half'), [mobileTab, setMobileTab] = useState('directions');
  const [navigating, setNavigating] = useState(false), [guidanceStep, setGuidanceStep] = useState(0), [reload, setReload] = useState(0);
  const operation = useRef(null), queryRevision = useRef(0), nearestOperation = useRef(null), trafficRevision = useRef(0), sheetDrag = useRef(null), sheetDragged = useRef(false);
  const trafficSaveOperation = useRef(null);
  const pending = busy || trafficSaving;

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    loadMap().then((network) => { if (!controller.signal.aborted) setMap(network); })
      .catch((err) => { if (!controller.signal.aborted) setError(err.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    refreshTraffic(controller.signal);
    return () => { controller.abort(); operation.current?.abort(); nearestOperation.current?.abort(); };
  }, [reload]);
  useEffect(() => () => { trafficSaveOperation.current?.abort(); trafficSaveOperation.current = null; }, []);
  useEffect(() => { onEndpointsChange?.({ origin, destination }); }, [origin, destination, onEndpointsChange]);
  useEffect(() => {
    if (!popover) return;
    const close = (event) => { if (event.key === 'Escape') setPopover(''); };
    window.addEventListener('keydown', close);
    return () => window.removeEventListener('keydown', close);
  }, [popover]);
  async function refreshTraffic(signal) {
    const revision = ++trafficRevision.current;
    try {
      const observations = await api.traffic({ signal });
      if (!signal?.aborted && revision === trafficRevision.current) setTraffic(observations);
    } catch (err) { if (!signal?.aborted && revision === trafficRevision.current) setError(err.message); }
  }
  function invalidate() {
    queryRevision.current++; operation.current?.abort(); nearestOperation.current?.abort();
    setBusy(false); setPlan(null); setQuota(false); setError(''); setNavigating(false); setGuidanceStep(0);
  }
  function choose(setter, place) { invalidate(); setter(place); }
  async function nearest(point) {
    if (trafficSaveOperation.current) return;
    nearestOperation.current?.abort(); const controller = new AbortController(); nearestOperation.current = controller;
    const revision = queryRevision.current, endpoint = pick;
    setError(''); setBusy(true);
    try {
      const result = await api.nearest({ ...point, mode }, { signal: controller.signal });
      if (controller.signal.aborted || revision !== queryRevision.current) return;
      choose(endpoint === 'origin' ? setOrigin : setDestination, result.place);
      if (endpoint === 'origin') setPick('destination');
    } catch (err) { if (!controller.signal.aborted) setError(err.message); }
    finally { if (nearestOperation.current === controller) { setBusy(false); nearestOperation.current = null; } }
  }
  async function run(count = 3, points = { origin, destination }) {
    if (trafficSaveOperation.current || !points.origin || !points.destination) return;
    operation.current?.abort(); const controller = new AbortController(); operation.current = controller;
    const revision = queryRevision.current; setBusy(true); setError(''); setQuota(false); setPlan(null); setNavigating(false);
    try {
      const result = await api.route({ origin: points.origin.id, destination: points.destination.id, mode, preference, alternatives: count,
        avoidRoads: avoid.split(',').map((road) => road.trim()).filter(Boolean) }, { signal: controller.signal });
      if (!controller.signal.aborted && revision === queryRevision.current) {
        const routes = visibleRoutes(result.routes);
        setPlan({ ...result, routes }); setSelected(0); setSheet('half'); setMobileTab('directions');
        if (!routes.length) setError('Không tìm được đường đi giữa hai điểm này. Hãy thử đổi phương tiện hoặc chọn điểm gần đường chính.');
      }
    } catch (err) {
      if (!controller.signal.aborted && revision === queryRevision.current) {
        setError(`${err.message} Hãy thử đổi phương tiện hoặc điểm đến.`); setQuota(err.code === 'ROUTE_LIMIT_EXCEEDED' && count > 1);
      }
    } finally { if (operation.current === controller) { setBusy(false); operation.current = null; } }
  }
  async function exampleRoute() {
    if (trafficSaveOperation.current) return;
    invalidate(); setBusy(true);
    const controller = new AbortController(); operation.current = controller;
    try {
      const results = await Promise.all(['Ngõ 90 Đường Khuất Duy Tiến', 'Ngõ 178 Phố Quan Nhân'].map((q) => api.searchPlaces(q, { mode, signal: controller.signal })));
      if (controller.signal.aborted) return;
      const points = { origin: results[0].places[0], destination: results[1].places[0] };
      if (!points.origin || !points.destination) throw new Error('Chưa tìm thấy tuyến gợi ý. Hãy tìm điểm đi và điểm đến ở ô phía trên.');
      setOrigin(points.origin); setDestination(points.destination); await run(3, points);
    } catch (err) { if (!controller.signal.aborted) { setError(err.message); setBusy(false); } }
  }
  async function updateTraffic(clear = false) {
    if (trafficSaveOperation.current) return;
    const controller = new AbortController(); trafficSaveOperation.current = controller;
    invalidate(); trafficRevision.current++; setTrafficSaving(true); setTrafficMessage('');
    try {
      const body = clear ? { source: 'Local UI observation', observedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 600_000).toISOString(), updates: [] }
        : trafficBody(map.roads, segment, mode, speed, closed);
      const observations = await api.updateTraffic(body, { signal: controller.signal });
      if (controller.signal.aborted || trafficSaveOperation.current !== controller) return;
      invalidate(); trafficRevision.current++; setTraffic(observations);
      setTrafficMessage(clear ? 'Đã xóa snapshot giao thông.' : 'Đã thay snapshot; quan sát có hiệu lực trong 10 phút.');
    } catch (err) {
      if (!controller.signal.aborted && trafficSaveOperation.current === controller) setError(err.message);
    } finally {
      if (trafficSaveOperation.current === controller) { trafficSaveOperation.current = null; setTrafficSaving(false); }
    }
  }
  const route = plan?.routes[selected];
  const directions = mergeDirections(route?.directions);
  const roads = map?.roads.filter((road) => road.speedsKph?.[mode.toUpperCase()]) || [];
  const roadOptions = filterRoads(roads, roadQuery, segment);
  const summaryRoad = routeSummaryRoad(route);
  const toggle = (name) => setPopover((value) => value === name ? '' : name);
  function stepList() {
    return <ol className="directions" tabIndex={0} aria-label="Các bước chỉ đường">{directions.map((direction, index) => <li key={index} className={navigating && guidanceStep === index ? 'active-step' : ''}>
      <Icon name={MANEUVERS[direction.maneuver] || 'straight'} /><span>{directionText(direction, destination?.name)}</span>
      {direction.distanceMeters > 0 && <small>{formatDistance(direction.distanceMeters)}</small>}
    </li>)}</ol>;
  }
  return <main id="main-content" tabIndex={-1} className={`routing-workspace mobile-${mobileTab} sheet-${sheet}`}>
    {map && <MapView map={map} mode={mode} routes={plan?.routes} selectedRoute={selected} markers={[origin, destination]}
      onPick={pending ? undefined : nearest} is3D={is3D} showNodes={showNodes} navigating={navigating} onLocationError={setError} active={active} />}
    {loading && <div className="map-loading" role="status"><BrandMark size={42} /><strong>Đang mở bản đồ Thanh Xuân…</strong><span>Một hành trình mới đang chờ bạn.</span></div>}
    {!loading && !map && <div className="map-loading"><Icon name="map" size={42} /><strong>Chưa tải được bản đồ</strong><button onClick={() => { setError(''); setReload((value) => value + 1); }}>Thử lại</button></div>}

    <header className="map-toolbar" aria-label="Tùy chọn bản đồ">
      <div className="view-toggle floating-card" role="group" aria-label="Góc nhìn"><button aria-label="Góc nhìn 2D" aria-pressed={!is3D} onClick={() => setIs3D(window.innerWidth < 768 ? !is3D : false)}>2D</button><button aria-label="Góc nhìn 3D" aria-pressed={is3D} onClick={() => setIs3D(window.innerWidth < 768 ? !is3D : true)}><Icon name="cube" />3D</button></div>
      <button className="toolbar-chip" aria-label="Giao thông" aria-expanded={popover === 'traffic'} onClick={() => toggle('traffic')}><Icon name="traffic" /><span>Giao thông</span>{traffic?.active && <i className="status-dot" />}</button>
      <button className="toolbar-chip analysis-link" aria-label="Phân tích thuật toán" onClick={onAnalysis}><Icon name="graph" /><span>Phân tích thuật toán</span></button>
      <button className="toolbar-chip layers-chip" aria-label="Lớp bản đồ" aria-expanded={popover === 'layers'} onClick={() => toggle('layers')}><Icon name="layers" /><span>Lớp bản đồ</span></button>
    </header>

    <aside className="route-sidebar" aria-label="Tìm đường">
      <div className="global-search floating-card"><button className="icon-button" aria-label="Thông tin Youth's Map" onClick={() => toggle('about')}><Icon name="menu" /></button>
        <PlaceSearch className="global-place-search" label="Tìm địa điểm" hideLabel disabled={trafficSaving} placeholder="Tìm địa điểm ở Thanh Xuân, Hà Nội" mode={mode} value={null} onChange={(place) => { choose(setDestination, place); setMobileTab('directions'); }} />
        <span className="search-symbol"><Icon name="search" size={23} /></span>
      </div>
      <section className="directions-card floating-card" aria-labelledby="route-title">
        <div className="brand-heading"><BrandMark /><h1 id="route-title">Youth's Map</h1><span>Chỉ đường</span></div>
        <fieldset disabled={pending || loading} className="route-fields">
          <div className="mode-tabs" role="group" aria-label="Phương tiện">{Object.entries(MODES).map(([id, [name, icon]]) => <button type="button" key={id} aria-pressed={id === mode} onClick={() => { invalidate(); setMode(id); setSegment(''); }}><Icon name={icon} size={22} /><span>{name}</span></button>)}</div>
          <div className="endpoints"><div className="endpoint-marks" aria-hidden="true"><i /><span /><Icon name="pin" size={16} /></div>
            <div className="endpoint-fields"><div onFocus={() => setPick('origin')}><PlaceSearch label="Điểm đi" hideLabel disabled={trafficSaving} mode={mode} value={origin} onChange={(place) => { choose(setOrigin, place); setPick('destination'); }} placeholder="Chọn điểm xuất phát" /></div>
              <div onFocus={() => setPick('destination')}><PlaceSearch label="Điểm đến" hideLabel disabled={trafficSaving} mode={mode} value={destination} onChange={(place) => choose(setDestination, place)} placeholder="Bạn muốn đi đâu?" /></div></div>
            <button className="swap-button icon-button" type="button" aria-label="Đổi chiều" onClick={() => { invalidate(); setOrigin(destination); setDestination(origin); }}><Icon name="swap" /></button>
          </div>
          <div className="route-preferences"><div className="preference-toggle" role="group" aria-label="Ưu tiên tuyến đường"><button type="button" aria-pressed={preference === 'fastest'} onClick={() => { invalidate(); setPreference('fastest'); }}>Nhanh nhất</button><button type="button" aria-pressed={preference === 'shortest'} onClick={() => { invalidate(); setPreference('shortest'); }}>Ngắn nhất</button></div>
            <button type="button" className="avoid-button" aria-expanded={popover === 'avoid'} onClick={() => toggle('avoid')}><Icon name="avoid" size={18} />Tránh đường{avoid && <i className="status-dot" />}</button></div>
          <button className="primary find-route" type="button" disabled={!origin || !destination} onClick={() => run()}><Icon name="search" />{busy ? 'Đang tìm đường…' : route ? 'Tính lại tuyến đường' : 'Tìm đường'}</button>
        </fieldset>
        {!route && <button className="map-pick-hint" onClick={() => toggle('coordinates')}><Icon name="pin" size={15} />Hoặc chọn {pick === 'origin' ? 'điểm đi' : 'điểm đến'} trên bản đồ</button>}
      </section>
      {error && <div className="error floating-card" role="alert"><Icon name="info" /><div>{error}{quota && <button type="button" onClick={() => run(1)}>Thử lại chỉ với tuyến chính</button>}</div><button className="icon-button" aria-label="Đóng thông báo" onClick={() => setError('')}><Icon name="close" size={16} /></button></div>}
      {pending && <div className="busy-card floating-card" role="status"><BrandMark size={22} />{trafficSaving ? 'Đang lưu tình trạng giao thông…' : 'Đang tìm hành trình phù hợp…'}</div>}

      <section className="route-summary floating-card" aria-label="Kết quả tìm đường">
        <button className="sheet-handle" aria-label={sheet === 'full' ? 'Thu gọn bảng chỉ đường' : 'Mở rộng bảng chỉ đường'} onClick={() => { if (sheetDragged.current) { sheetDragged.current = false; return; } setSheet((value) => value === 'full' ? 'half' : 'full'); }}
          onPointerDown={(event) => { sheetDrag.current = event.clientY; event.currentTarget.setPointerCapture(event.pointerId); }}
          onPointerUp={(event) => { const movement = sheetDrag.current - event.clientY; if (Math.abs(movement) > 25) { sheetDragged.current = true; setSheet(movement > 0 ? (sheet === 'collapsed' ? 'half' : 'full') : (sheet === 'full' ? 'half' : 'collapsed')); } sheetDrag.current = null; }}><span /></button>
        {route ? <>
          <div className="mobile-route-heading"><strong>{formatDuration(route.durationSeconds)}</strong><span>{formatDistance(route.distanceMeters)}</span><p>{MODES[mode][0]} · {preference === 'fastest' ? 'Nhanh nhất' : 'Ngắn nhất'}{summaryRoad ? ` · qua ${summaryRoad}` : ''}</p></div>
          <h2 className="eyebrow desktop-route-heading">{plan.routes.length} tuyến đường</h2>
          <div className="route-options" aria-label="Các tuyến">{plan.routes.map((item, index) => {
            const road = routeSummaryRoad(item);
            const detail = index > 0 && Number.isFinite(item.sharedDistanceRatio)
              ? `Trùng ${Math.round(item.sharedDistanceRatio * 100)}% với tuyến chính`
              : road ? `qua ${road}` : 'Tuyến đề xuất';
            return <button key={index} className="route-card" type="button" aria-pressed={index === selected} onClick={() => { setSelected(index); setGuidanceStep(0); setNavigating(false); }}>
              <span><strong>{formatDuration(item.durationSeconds)}</strong><small>{formatDistance(item.distanceMeters)} · {detail}</small></span>
              {index === 0 && <span className="route-badge">{preference === 'fastest' ? 'Nhanh nhất' : 'Ngắn nhất'}</span>}
            </button>;
          })}</div>
          <div className="journey-actions"><button className="primary start-route" onClick={() => { setNavigating((value) => !value); setGuidanceStep(0); if (!navigating) setSheet('full'); }}><Icon name={navigating ? 'close' : 'navigate'} size={22} />{navigating ? 'Kết thúc chỉ dẫn' : 'Bắt đầu đi'}</button><button className="steps-button" onClick={() => setSheet((value) => value === 'full' ? 'half' : 'full')}><Icon name="list" />{directions.length} bước</button></div>
          <div className="mobile-step-list">{stepList()}</div>
        </> : <div className="empty-routes"><span className="eyebrow">THANH XUÂN, HÀ NỘI</span><h2>Đi đâu cũng có đường.</h2><p>Chọn điểm đi và điểm đến để tìm hành trình của bạn.</p><button className="example-route" disabled={pending || loading || !map} onClick={exampleRoute}><span><Icon name="navigate" /><span>Thử một hành trình<small>Khuất Duy Tiến → Quan Nhân</small></span></span><Icon name="arrowLeft" className="flipped" /></button></div>}
      </section>
      {route && <section className="steps-card floating-card"><h2 className="eyebrow">Chỉ dẫn từng bước <span>{directions.length} bước</span></h2>{stepList()}<p className="eta-note">Thời gian di chuyển là ước tính.</p></section>}
      <div className="sidebar-footer"><span className="status-dot" />Dữ liệu OpenStreetMap · Thanh Xuân</div>
    </aside>

    {navigating && directions.length > 0 && <section className="navigation-banner floating-card" aria-label="Chỉ dẫn hiện tại"><Icon name={MANEUVERS[directions[guidanceStep].maneuver]} size={30} /><div><small>Bước {guidanceStep + 1} / {directions.length}</small><strong>{directionText(directions[guidanceStep], destination?.name)}</strong></div><button aria-label="Chỉ dẫn tiếp theo" disabled={guidanceStep >= directions.length - 1} onClick={() => setGuidanceStep((value) => value + 1)}><Icon name="skipForward" /></button></section>}

    {popover && <button className="popover-backdrop" aria-label="Đóng bảng tùy chọn" onClick={() => setPopover('')} />}
    <section className="traffic-panel floating-card popover" hidden={popover !== 'traffic'} aria-labelledby="traffic-title">
      <div className="popover-heading"><h2 id="traffic-title">Giao thông</h2><button className="icon-button" aria-label="Đóng giao thông" onClick={() => setPopover('')}><Icon name="close" /></button></div>
      <p role="status">{traffic?.active ? `Đang áp dụng ${traffic.updates} quan sát · ${traffic.source} · hết hạn ${new Date(traffic.expiresAt).toLocaleTimeString('vi-VN')}` : 'Chưa có quan sát giao thông đang hiệu lực.'}</p>
      <p className="hint">Nhập tình trạng đoạn đường để tính lại hành trình. Mỗi lần lưu thay các quan sát trước đó và có hiệu lực 10 phút.</p>
      <form className="traffic-form" onSubmit={(event) => { event.preventDefault(); updateTraffic(); }}>
        <label>Lọc cung đường<input type="search" placeholder="Tên đường hoặc mã đoạn đường" value={roadQuery} onChange={(event) => setRoadQuery(event.target.value)} /></label>
        <label className="traffic-road">Cung đường ({MODES[mode][0]})<select required value={segment} disabled={pending || !map} onChange={(event) => setSegment(event.target.value)}><option value="">Chọn đoạn đường và chiều đi</option>{roadOptions.visible.map((road) => <option key={road.id} value={road.id}>{road.name || 'Đường không tên'} · {road.id} · {road.source} → {road.target}</option>)}</select></label>
        <label>Tốc độ (km/h)<input type="number" min="0.1" max="200" step="any" required={!closed} disabled={pending || closed} value={speed} onChange={(event) => setSpeed(event.target.value)} /></label>
        <label className="checkbox-label"><input type="checkbox" checked={closed} disabled={pending} onChange={(event) => setClosed(event.target.checked)} />Đóng đoạn đường</label>
        <button className="primary" type="submit" disabled={pending || !segment}>Lưu tình trạng</button><button type="button" disabled={pending || !map} onClick={() => updateTraffic(true)}>Xóa snapshot</button>
      </form><button className="text-button" disabled={pending} onClick={() => refreshTraffic()}>Làm mới trạng thái</button>
      {trafficMessage && <p className="info-box" role="status">{trafficMessage}</p>}
    </section>
    {popover === 'layers' && <section className="popover floating-card layers-popover"><h2>Lớp bản đồ</h2><label className="checkbox-label"><input type="checkbox" checked={is3D} onChange={(event) => setIs3D(event.target.checked)} />Góc nhìn 3D</label><label className="checkbox-label"><input type="checkbox" checked={showNodes} onChange={(event) => setShowNodes(event.target.checked)} />Nút mạng đường</label><p className="hint">Bản đồ hiện có dữ liệu đường phố. Lớp toà nhà sẽ hiển thị khi có dữ liệu OSM tương ứng.</p></section>}
    {popover === 'avoid' && <section className="popover floating-card"><h2>Tránh đường</h2><label>Tên đường cần tránh<input value={avoid} onChange={(event) => { invalidate(); setAvoid(event.target.value); }} placeholder="Ví dụ: Nguyễn Trãi, Vũ Trọng Phụng" /></label><p className="hint">Ngăn cách các tên đường bằng dấu phẩy.</p><button className="primary" onClick={() => setPopover('')}>Áp dụng</button></section>}
    {popover === 'coordinates' && <section className="popover floating-card"><h2>Chọn điểm trên bản đồ</h2><label>Chọn cho<select value={pick} onChange={(event) => setPick(event.target.value)}><option value="origin">Điểm đi</option><option value="destination">Điểm đến</option></select></label><p className="hint">Đóng bảng này rồi chạm một điểm trên bản đồ, hoặc nhập tọa độ bên dưới.</p><form className="coordinate-form" onSubmit={(event) => { event.preventDefault(); nearest({ lat: Number(coordinate.lat), lng: Number(coordinate.lng) }); setPopover(''); }}><label>Vĩ độ<input type="number" min="-90" max="90" step="any" required value={coordinate.lat} onChange={(event) => setCoordinate({ ...coordinate, lat: event.target.value })} /></label><label>Kinh độ<input type="number" min="-180" max="180" step="any" required value={coordinate.lng} onChange={(event) => setCoordinate({ ...coordinate, lng: event.target.value })} /></label><button className="primary" disabled={pending || loading}>Chọn nút gần nhất</button></form></section>}
    {popover === 'about' && <section className="popover floating-card about-popover"><BrandMark size={52} /><h2>Youth's Map</h2><p>Tìm đường nhanh, đi đường trẻ.</p><p className="hint">Khám phá mạng đường Thanh Xuân, Hà Nội, và cách các thuật toán tìm ra hành trình của bạn.</p><button onClick={() => { setPopover(''); onAnalysis?.(); }}><Icon name="graph" />Khám phá thuật toán</button></section>}
    <nav className="mobile-nav" aria-label="Điều hướng chính"><button aria-pressed={mobileTab === 'map'} onClick={() => setMobileTab('map')}><Icon name="map" />Bản đồ</button><button aria-pressed={mobileTab === 'directions'} onClick={() => setMobileTab('directions')}><Icon name="navigate" />Chỉ đường</button><button onClick={onAnalysis}><Icon name="graph" />Thuật toán</button></nav>
  </main>;
}
