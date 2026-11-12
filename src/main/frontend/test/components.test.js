import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { setTimeout as delay } from 'node:timers/promises';
import { fileURLToPath } from 'node:url';
import { JSDOM } from 'jsdom';
import { createServer } from 'vite';
import { act, createElement } from 'react';

let vite, dom, root, container, PlaceSearch, RoutingWorkspace, ControlPanel, createRoot;
const originalFetch = globalThis.fetch;
const originalGlobals = new Map();

before(async () => {
  dom = new JSDOM('<!doctype html><html><body></body></html>', { url: 'http://localhost/' });
  for (const [key, value] of Object.entries({
    window: dom.window, document: dom.window.document,
    IS_REACT_ACT_ENVIRONMENT: true,
  })) {
    originalGlobals.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, configurable: true, writable: true });
  }
  // These tests exercise requests and UI state, not browser canvas rendering.
  dom.window.HTMLCanvasElement.prototype.getContext = () => new Proxy({}, {
    get: () => () => {}, set: () => true,
  });
  ({ createRoot } = await import('react-dom/client'));
  vite = await createServer({
    root: fileURLToPath(new URL('..', import.meta.url)), configFile: false,
    // The React refresh plugin adds browser dependencies to Vite's shared optimizer cache.
    esbuild: { jsx: 'automatic' }, optimizeDeps: { noDiscovery: true },
    server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: 'custom',
  });
  PlaceSearch = (await vite.ssrLoadModule('/src/components/PlaceSearch.jsx')).default;
  RoutingWorkspace = (await vite.ssrLoadModule('/src/components/RoutingWorkspace.jsx')).default;
  ControlPanel = (await vite.ssrLoadModule('/src/components/ControlPanel.jsx')).default;
});

afterEach(async () => {
  if (root) await act(async () => root.unmount());
  root = null;
  container?.remove();
  globalThis.fetch = originalFetch;
});

after(async () => {
  await vite?.close();
  dom?.window.close();
  for (const [key, descriptor] of originalGlobals) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor);
    else delete globalThis[key];
  }
});

async function render(Component, props = {}) {
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  await act(async () => root.render(createElement(Component, props)));
}

async function type(input, value) {
  await act(async () => {
    Object.getOwnPropertyDescriptor(dom.window.HTMLInputElement.prototype, 'value').set.call(input, value);
    input.dispatchEvent(new dom.window.Event('input', { bubbles: true }));
  });
}

async function search(query, input = container.querySelector('input[type="search"]')) {
  await type(input, query);
  await act(async () => { await delay(280); });
}

function json(body, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  }));
}

const place = { id: 'a', name: 'Nguyễn Trãi', displayName: 'Nguyễn Trãi',
  latitude: 21, longitude: 105.8, modes: ['driving', 'motorcycling'], mainComponentModes: ['driving', 'motorcycling'] };
const destination = { ...place, id: 'b', name: 'Lê Văn Lương', displayName: 'Lê Văn Lương', longitude: 105.81 };
const road = { id: 'arc:0', source: 'a', target: 'b', name: 'Main', sourceWayId: '1',
  speedsKph: { DRIVING: 30, MOTORCYCLING: 30 }, geometry: [place, destination] };
// Routing mounts share the application's cached map, so their static network is identical.
const network = { places: [place, destination], roads: [road] };
const primaryRoute = { distanceMeters: 1000, durationSeconds: 120, sharedDistanceRatio: 1,
  geometry: { type: 'LineString', coordinates: [[105.8, 21], [105.81, 21]] },
  directions: [{ maneuver: 'CONTINUE', roadName: 'Nguyễn Trãi', distanceMeters: 1000 }] };

async function runExampleRoute(onRoute) {
  globalThis.fetch = (url, options) => {
    if (url === '/api/map') return json(network);
    if (url === '/api/traffic') return json({ active: false });
    if (String(url).startsWith('/api/places')) {
      const query = new URL(url, 'http://localhost').searchParams.get('q');
      return json({ places: [query.includes('Khuất Duy Tiến') ? place : destination], total: 1, offset: 0 });
    }
    if (url === '/api/routes') return onRoute(JSON.parse(options.body));
    throw new Error(`Unexpected request: ${url}`);
  };
  await render(RoutingWorkspace);
  await act(async () => container.querySelector('.example-route').click());
}

for (const [scenario, capability, disabled] of [
  ['supported', { available: true, message: 'Ready' }, false],
  ['unsupported', { available: false, message: 'Requires an undirected graph' }, true],
  ['missing', null, false],
]) {
test(`algorithm controls handle a ${scenario} graph descriptor`, async () => {
  const graphs = [{ id: 'other', nodes: 0, algorithms: [{ algorithm: 'DIJKSTRA', available: false }] }];
  if (capability) graphs.push({ id: 'sample', nodes: 0, algorithms: [{ algorithm: 'DIJKSTRA', ...capability }] });
  await render(ControlPanel, { workspace: {
    graphs, algorithms: [{ id: 'DIJKSTRA', name: 'Dijkstra', category: 'SHORTEST_PATH', requiresStart: true, requiresGoal: true }],
    graph: { nodes: [] }, selectedGraph: 'sample', setSelectedGraph() {},
    form: { algorithm: 'DIJKSTRA', startId: '', goalId: '', maxTraceSteps: 1000 }, setForm() {},
    loading: false, running: false, editing: false, run() {},
  } });
  const option = container.querySelector('button[data-algorithm="DIJKSTRA"]');
  assert.equal(option.disabled, disabled);
  assert.equal(container.querySelector('button.primary').disabled, disabled);
  assert.equal(option.title, capability?.message || '');
  assert.equal(container.textContent.includes('Requires an undirected graph'), disabled);
});
}

test('changing the query immediately removes suggestions from the previous search', async () => {
  globalThis.fetch = (url) => String(url).includes('q=old')
    ? json({ places: [place], total: 1, offset: 0 }) : new Promise(() => {});
  await render(PlaceSearch, { label: 'Điểm đi', mode: 'driving', onChange() {} });
  await search('old');
  assert.equal(container.querySelectorAll('.search-results button').length, 1);
  await type(container.querySelector('input'), 'new');
  assert.equal(container.querySelectorAll('.search-results button').length, 0,
    'Old suggestions must not remain selectable while a different query is pending');
});

test('a failed replacement search does not leave old suggestions selectable', async () => {
  globalThis.fetch = (url) => String(url).includes('q=old')
    ? json({ places: [place], total: 1, offset: 0 })
    : json({ error: { code: 'SEARCH_FAILED', message: 'Search unavailable' } }, 503);
  await render(PlaceSearch, { label: 'Điểm đi', mode: 'driving', onChange() {} });
  await search('old');
  await search('new');
  assert.match(container.textContent, /Search unavailable/);
  assert.equal(container.querySelectorAll('.search-results button').length, 0);
});

test('keyboard search selects the highlighted place and Escape dismisses without selecting', async () => {
  const selections = [];
  globalThis.fetch = () => json({ places: [place, destination], total: 2, offset: 0 });
  await render(PlaceSearch, { label: 'Điểm đến', mode: 'motorcycling', onChange: (value) => selections.push(value) });
  const input = container.querySelector('input');
  const key = (value) => act(async () => input.dispatchEvent(
    new dom.window.KeyboardEvent('keydown', { key: value, bubbles: true, cancelable: true })));
  await act(async () => input.focus());
  await search('đường', input);
  await key('ArrowDown');
  await key('ArrowDown');
  const highlighted = document.getElementById(input.getAttribute('aria-activedescendant'));
  assert.match(highlighted.textContent, /Lê Văn Lương/);
  assert.equal(highlighted.getAttribute('aria-selected'), 'true');
  await key('Enter');
  assert.deepEqual(selections, [destination]);
  assert.equal(input.getAttribute('aria-expanded'), 'false');

  await search('đường khác', input);
  await key('ArrowDown');
  await key('Escape');
  await key('Enter');
  assert.equal(container.querySelector('[role="listbox"]'), null);
  assert.equal(input.getAttribute('aria-expanded'), 'false');
  assert.equal(selections.length, 1, 'Dismissing suggestions must not select another place');
});

test('ArrowUp from an unselected search list selects its last suggestion', async () => {
  let selected;
  globalThis.fetch = () => json({ places: [place, destination], total: 2, offset: 0 });
  await render(PlaceSearch, { label: 'Điểm đến', mode: 'motorcycling', onChange: (value) => { selected = value; } });
  const input = container.querySelector('input');
  await search('đường', input);
  await act(async () => input.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'ArrowUp', bubbles: true })));
  await act(async () => input.dispatchEvent(new dom.window.KeyboardEvent('keydown', { key: 'Enter', bubbles: true })));
  assert.deepEqual(selected, destination);
});

test('a traffic status failure does not discard the successfully loaded map', async () => {
  globalThis.fetch = (url) => String(url) === '/api/map'
    ? json(network)
    : json({ error: { code: 'TRAFFIC_FAILED', message: 'Traffic unavailable' } }, 503);
  await render(RoutingWorkspace);
  assert.ok(container.querySelector('canvas'), 'The map should remain available without traffic status');
  assert.match(container.textContent, /Traffic unavailable/);
  assert.equal(container.querySelector('fieldset').disabled, false);
});

test('the analysis toolbar button has a name when its text is hidden and opens analysis', async () => {
  let openings = 0;
  globalThis.fetch = (url) => url === '/api/map' ? json(network) : json({ active: false });
  await render(RoutingWorkspace, { onAnalysis: () => { openings++; } });

  const button = container.querySelector('.map-toolbar .analysis-link');
  const label = button.querySelector('span');
  assert.equal(label.textContent, 'Phân tích thuật toán');
  label.hidden = true;
  assert.equal(button.getAttribute('aria-label'), 'Phân tích thuật toán');
  await act(async () => button.click());
  assert.equal(openings, 1);
});

test('selecting a different route keeps the primary route card’s own road name', async () => {
  const alternative = { ...primaryRoute, distanceMeters: 1200, durationSeconds: 140, sharedDistanceRatio: 0.2,
    geometry: { type: 'LineString', coordinates: [[105.8, 21], [105.805, 21.001], [105.81, 21]] },
    directions: [{ maneuver: 'CONTINUE', roadName: 'Lê Văn Lương', distanceMeters: 1200 }] };
  await runExampleRoute(() => json({ routes: [primaryRoute, alternative] }));

  const [primary, other] = container.querySelectorAll('.route-card');
  assert.match(primary.textContent, /qua Nguyễn Trãi/);
  await act(async () => other.click());
  assert.equal(other.getAttribute('aria-pressed'), 'true');
  assert.match(container.querySelector('.mobile-route-heading').textContent, /qua Lê Văn Lương/);
  assert.match(primary.textContent, /qua Nguyễn Trãi/);
});

test('the routing quota retry requests only the primary route and clears the error on success', async () => {
  const counts = [];
  await runExampleRoute(({ alternatives }) => {
    counts.push(alternatives);
    return alternatives === 1 ? json({ routes: [primaryRoute] })
      : json({ error: { code: 'ROUTE_LIMIT_EXCEEDED', message: 'Reduce alternatives' } }, 422);
  });

  const alert = container.querySelector('[role="alert"]');
  assert.match(alert.textContent, /Reduce alternatives/);
  assert.equal(container.querySelectorAll('.route-card').length, 0);
  await act(async () => alert.querySelector('div button').click());
  assert.deepEqual(counts, [3, 1]);
  assert.equal(container.querySelector('[role="alert"]'), null);
  assert.equal(container.querySelectorAll('.route-card').length, 1);
});

for (const scenario of ['initial status failure', 'late initial status', 'late initial error', 'initial error during save']) {
test(`saving traffic preserves the new status and clears the route despite ${scenario}`, { timeout: 10000 }, async () => {
  const inactive = { active: false, source: 'none', observedAt: null, expiresAt: null, updates: 0 };
  const active = { active: true, source: 'Local UI observation', observedAt: '2026-10-25T00:00:00Z',
    expiresAt: '2026-10-25T00:10:00Z', updates: 1 };
  let statusRequests = 0;
  let finishInitial, finishUpdate, routeRequest, trafficRequest;
  globalThis.fetch = (url, options) => {
    if (url === '/api/map') return json(network);
    if (url === '/api/traffic') {
      if (++statusRequests === 1 && scenario !== 'initial status failure') {
        return new Promise((resolve) => { finishInitial = resolve; });
      }
      return json({ error: { code: 'TRAFFIC_FAILED', message: 'Status unavailable' } }, 503);
    }
    if (String(url).startsWith('/api/places')) {
      const selected = new URL(url, 'http://localhost').searchParams.get('q') === 'origin' ? place : destination;
      return json({ places: [selected], total: 1, offset: 0 });
    }
    if (url === '/api/routes') {
      routeRequest = JSON.parse(options.body);
      return json({ routes: [{ distanceMeters: 1000, durationSeconds: 120,
        geometry: { type: 'LineString', coordinates: [[105.8, 21], [105.81, 21]] },
        directions: [{ maneuver: 'ARRIVE', distanceMeters: 0 }] }] });
    }
    if (url === '/api/traffic/update') {
      trafficRequest = JSON.parse(options.body);
      return scenario === 'initial error during save'
        ? new Promise((resolve) => { finishUpdate = resolve; }) : json(active);
    }
    throw new Error(`Unexpected request: ${url}`);
  };
  await render(RoutingWorkspace);
  if (scenario === 'initial status failure') assert.match(container.querySelector('[role="alert"]').textContent, /Status unavailable/);
  const inputs = container.querySelectorAll('.place-search input');
  await search('origin', inputs[0]);
  await act(async () => container.querySelector('.search-results button').click());
  await search('destination', inputs[1]);
  await act(async () => container.querySelector('.search-results button').click());
  await act(async () => container.querySelector('.find-route').click());
  assert.deepEqual(routeRequest, { origin: 'a', destination: 'b', mode: 'motorcycling', preference: 'fastest', alternatives: 3, avoidRoads: [] });
  assert.ok(container.querySelector('.route-options'));
  const trafficButton = [...container.querySelectorAll('.toolbar-chip')].find((button) => button.textContent === 'Giao thông');
  await act(async () => trafficButton.click());
  assert.equal(container.querySelector('.traffic-panel').hidden, false);
  await act(async () => {
    const select = container.querySelector('.traffic-road select');
    select.value = 'arc:0';
    select.dispatchEvent(new dom.window.Event('change', { bubbles: true }));
  });
  await act(async () => container.querySelector('.traffic-form').dispatchEvent(
    new dom.window.Event('submit', { bubbles: true, cancelable: true })));
  assert.deepEqual(trafficRequest.updates, [{ from: 'a', to: 'b', sourceWayId: '1', segmentId: 'arc:0', mode: 'motorcycling', speedKph: 10, closed: false }]);
  if (finishUpdate) {
    await act(async () => finishInitial(await json({ error: { code: 'TRAFFIC_FAILED', message: 'Old status error' } }, 503)));
    await act(async () => finishUpdate(await json(active)));
    finishInitial = null;
  }
  if (finishInitial) await act(async () => {
    finishInitial(await (scenario === 'late initial error'
      ? json({ error: { code: 'TRAFFIC_FAILED', message: 'Old status error' } }, 503) : json(inactive)));
  });
  assert.equal(!!container.querySelector('.route-options'), false, 'The route calculated before the traffic update is stale');
  assert.match(container.querySelector('.traffic-panel').textContent, /Đang áp dụng 1 quan sát/);
  assert.equal(!!container.querySelector('[role="alert"]'), false, 'An obsolete status error must not replace a successful save');
  assert.equal(statusRequests, 1, 'Saving uses the returned status instead of replacing it with another fetch');
});
}

for (const succeeds of [true, false]) {
test(`a pending traffic save blocks endpoint changes and duplicate mutations, then unlocks after ${succeeds ? 'success' : 'failure'}`, async () => {
  let finishUpdate, saveRequests = 0;
  globalThis.fetch = (url) => {
    if (url === '/api/map') return json(network);
    if (url === '/api/traffic') return json({ active: false });
    if (String(url).startsWith('/api/places')) return json({ places: [destination], total: 1, offset: 0 });
    if (url === '/api/traffic/update') {
      saveRequests++;
      return new Promise((resolve) => { finishUpdate = resolve; });
    }
    throw new Error(`Unexpected request: ${url}`);
  };
  await render(RoutingWorkspace);
  const globalSearch = container.querySelector('.global-place-search input');
  await search('destination', globalSearch);
  assert.ok(container.querySelector('.global-place-search [role="listbox"]'));
  await act(async () => container.querySelector('[aria-label="Giao thông"]').click());
  await act(async () => {
    const select = container.querySelector('.traffic-road select');
    select.value = 'arc:0';
    select.dispatchEvent(new dom.window.Event('change', { bubbles: true }));
  });
  const form = container.querySelector('.traffic-form');
  await act(async () => form.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })));
  assert.equal(globalSearch.disabled, true, 'The global search must not reset route busy state while a mutation is pending');
  assert.equal(container.querySelector('.global-place-search [role="listbox"]'), null);
  await act(async () => container.querySelector('[aria-label="Đóng giao thông"]').click());
  await act(async () => container.querySelector('[aria-label="Giao thông"]').click());
  assert.equal(form.querySelector('[type="submit"]').disabled, true);
  await act(async () => form.dispatchEvent(new dom.window.Event('submit', { bubbles: true, cancelable: true })));
  assert.equal(saveRequests, 1, 'Even a second submit event must not create an overlapping mutation');
  await act(async () => finishUpdate(await (succeeds
    ? json({ active: true, source: 'Saved observation', expiresAt: '2026-10-27T23:00:00Z', updates: 1 })
    : json({ error: { code: 'TRAFFIC_FAILED', message: 'Save unavailable' } }, 503))));
  assert.equal(globalSearch.disabled, false);
  assert.equal(form.querySelector('[type="submit"]').disabled, false);
  if (succeeds) assert.match(container.querySelector('.traffic-panel [role="status"]').textContent, /Saved observation/);
  else assert.match(container.querySelector('[role="alert"]').textContent, /Save unavailable/);
});
}
