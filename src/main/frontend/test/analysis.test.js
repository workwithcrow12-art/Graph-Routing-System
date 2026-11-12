import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { act, createElement } from 'react';
import { JSDOM } from 'jsdom';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';
import { useGraphWorkspace } from '../src/hooks/useGraphWorkspace.js';

let dom, root, createRoot, workspace, vite, AnalysisWorkspace;
const previousGlobals = new Map();
const originalFetch = globalThis.fetch;
const graph = {
  graph: 'loaded', graphType: 'DIRECTED', revision: 0,
  nodes: [
    { id: 'a', name: 'Điểm A', latitude: 21, longitude: 105.8 },
    { id: 'b', name: 'Điểm B', latitude: 21.01, longitude: 105.81 },
    { id: 'c', name: 'Điểm C', latitude: 21.02, longitude: 105.82 },
  ], edges: [{ source: 'a', target: 'c', weight: 1, roadName: 'Đường thử' }],
};
const algorithms = ['DIJKSTRA', 'ASTAR'].map((id) => ({ id, name: id,
  category: 'SHORTEST_PATH', requiresStart: true, requiresGoal: true }));

function response(algorithm, visitedNodes) {
  return { algorithm, status: 'SUCCESS', nodeIds: ['a', 'c'],
    edges: [{ sourceId: 'a', destinationId: 'c', weight: 1, roadName: 'Đường thử' }],
    totalCost: 1, trace: [{ index: 0, event: 'SETTLE', nodeId: 'a', cost: 0, edge: null }],
    metrics: { graphNodes: 3, graphEdges: 1, graphArcs: 1, visitedNodes, resultEdges: 1,
      traceStepCount: visitedNodes, elapsedNanos: 2000, traceTruncated: true }, message: 'Completed.' };
}
const json = (data) => Promise.resolve(new Response(JSON.stringify(data), {
  status: 200, headers: { 'Content-Type': 'application/json' },
}));

before(async () => {
  dom = new JSDOM('<!doctype html><html><body><div id="test"></div></body></html>', { url: 'http://localhost' });
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    IS_REACT_ACT_ENVIRONMENT: true })) {
    previousGlobals.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
  }
  ({ createRoot } = await import('react-dom/client'));
  dom.window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
  vite = await createServer({
    root: fileURLToPath(new URL('..', import.meta.url)), configFile: false,
    esbuild: { jsx: 'automatic' }, optimizeDeps: { noDiscovery: true },
    server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: 'custom',
  });
  AnalysisWorkspace = (await vite.ssrLoadModule('/src/components/AnalysisWorkspace.jsx')).default;
});
afterEach(async () => {
  if (root) await act(async () => root.unmount());
  root = null;
  globalThis.fetch = originalFetch;
});
after(async () => {
  await vite?.close();
  dom.window.close();
  for (const [key, descriptor] of previousGlobals) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor);
    else delete globalThis[key];
  }
});

async function mount(onRun = (request) => json(response(request.algorithm, 3)), Component, props) {
  const graphRequests = [];
  globalThis.fetch = (url, options) => {
    if (url === '/api/analysis/graphs') return json([{ id: 'loaded', nodes: 3, arcs: 1,
      graphType: 'DIRECTED', editable: false,
      algorithms: algorithms.map(({ id }) => ({ algorithm: id, available: true, message: 'Supported.' })) }]);
    if (url === '/api/algorithms') return json(algorithms);
    if (String(url).startsWith('/api/analysis/graph?')) {
      graphRequests.push(new URL(url, 'http://localhost').searchParams.get('graph'));
      return json(graph);
    }
    if (url === '/api/algorithms/run') return onRun(JSON.parse(options.body));
    throw new Error(`Unexpected request ${url}`);
  };
  function Harness() { workspace = useGraphWorkspace(); return null; }
  root = createRoot(document.querySelector('#test'));
  await act(async () => root.render(createElement(Component || Harness, props)));
  return graphRequests;
}

test('analysis initially loads the OSM graph', async () => {
  const requested = await mount();
  assert.deepEqual(requested, ['loaded']);
  assert.equal(workspace.form.startId, 'a');
  assert.equal(workspace.form.goalId, 'c');
});

test('comparison retains actual visited counts, replaces reruns, and hides mismatched endpoints', async () => {
  let dijkstraRuns = 0;
  await mount((request) => json(response(request.algorithm,
    request.algorithm === 'DIJKSTRA' ? ++dijkstraRuns + 1 : 1)));
  await act(async () => workspace.run());
  await act(async () => workspace.setForm((form) => ({ ...form, algorithm: 'ASTAR' })));
  await act(async () => workspace.run());
  assert.deepEqual(workspace.comparisons.map((run) => [run.algorithm, run.metrics.visitedNodes]),
    [['DIJKSTRA', 2], ['ASTAR', 1]]);
  await act(async () => workspace.setForm((form) => ({ ...form, algorithm: 'DIJKSTRA' })));
  await act(async () => workspace.run());
  assert.equal(workspace.comparisons.length, 2);
  assert.equal(workspace.comparisons.find((run) => run.algorithm === 'DIJKSTRA').metrics.visitedNodes, 3);
  await act(async () => workspace.setForm((form) => ({ ...form, goalId: 'b' })));
  assert.deepEqual(workspace.comparisons, []);
  assert.equal(workspace.result, null);
});

test('a run finishing after an endpoint change is not displayed for the new route', async () => {
  let complete;
  await mount(() => new Promise((resolve) => { complete = resolve; }));
  let pending;
  await act(async () => { pending = workspace.run(); });
  await act(async () => workspace.setForm((form) => ({ ...form, goalId: 'b' })));
  await act(async () => { complete(await json(response('DIJKSTRA', 3))); await pending; });
  assert.equal(workspace.result, null);
  assert.deepEqual(workspace.comparisons, []);
});

test('invalid trace limits never reach the algorithm endpoint', async () => {
  let requests = 0;
  await mount((request) => { requests++; return json(response(request.algorithm, 3)); });
  for (const maxTraceSteps of [-1, 10001, 1.5, '']) {
    await act(async () => workspace.setForm((form) => ({ ...form, maxTraceSteps })));
    await act(async () => workspace.run());
    assert.ok(workspace.error);
  }
  assert.equal(requests, 0);
  await act(async () => workspace.setForm((form) => ({ ...form, maxTraceSteps: 10000 })));
  await act(async () => workspace.run());
  assert.equal(requests, 1);
});

test('half-speed playback slows a short trace and manual steps pause it', async () => {
  const run = response('DIJKSTRA', 3);
  run.trace = ['a', 'b', 'c'].map((nodeId, index) => ({ index, nodeId, event: 'SETTLE', cost: index, edge: null }));
  const originalSetTimeout = dom.window.setTimeout;
  const originalClearTimeout = dom.window.clearTimeout;
  let now = 0, timerId = 0;
  const pending = new Map();
  dom.window.setTimeout = (callback, duration) => {
    const id = ++timerId; pending.set(id, { callback, due: now + duration }); return id;
  };
  dom.window.clearTimeout = (id) => pending.delete(id);
  async function advance(ms) {
    const target = now + ms;
    while (true) {
      const next = [...pending].sort((a, b) => a[1].due - b[1].due)[0];
      if (!next || next[1].due > target) break;
      now = next[1].due;
      pending.delete(next[0]);
      await act(async () => next[1].callback());
    }
    now = target;
  }
  try {
    await mount(() => json(run), AnalysisWorkspace);
    await act(async () => document.querySelector('.analysis-run').click());
    await act(async () => [...document.querySelectorAll('.analysis-speed button')].find((button) => button.textContent === '0,5×').click());
    await act(async () => document.querySelector('[aria-label="Phát các bước"]').click());
    await advance(75);
    assert.equal(document.querySelector('[aria-label="Bước thuật toán"]').value, '0');
    await advance(25);
    assert.equal(document.querySelector('[aria-label="Bước thuật toán"]').value, '1');
    await act(async () => document.querySelector('[aria-label="Bước sau"]').click());
    await advance(300);
    assert.equal(document.querySelector('[aria-label="Bước thuật toán"]').value, '2');
    await act(async () => document.querySelector('[aria-label="Bước trước"]').click());
    assert.equal(document.querySelector('[aria-label="Bước thuật toán"]').value, '1');
  } finally {
    dom.window.setTimeout = originalSetTimeout;
    dom.window.clearTimeout = originalClearTimeout;
  }
});

test('reduced motion shows the finished result and keeps manual playback available', async () => {
  dom.window.matchMedia = () => ({ matches: true, addEventListener() {}, removeEventListener() {} });
  try {
    await mount(() => json(response('DIJKSTRA', 3)), AnalysisWorkspace);
    await act(async () => document.querySelector('.analysis-run').click());
    assert.equal(document.querySelector('[aria-label="Bước thuật toán"]').value, '1');
    assert.equal(document.querySelector('.analysis-play-button').disabled, true);
    await act(async () => document.querySelector('[aria-label="Bước trước"]').click());
    assert.equal(document.querySelector('[aria-label="Bước thuật toán"]').value, '0');
    assert.equal(document.querySelector('[aria-label="Bước sau"]').disabled, false);
  } finally {
    dom.window.matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
  }
});
