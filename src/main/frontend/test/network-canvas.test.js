import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { act, createElement } from 'react';
import { JSDOM } from 'jsdom';
import { fileURLToPath } from 'node:url';
import { createServer } from 'vite';

let dom, vite, root, createRoot, NetworkCanvas, resize, bounds, arcs, fills;
const previousGlobals = new Map();
const nodes = [
  { id: 'a', latitude: -1, longitude: -1 },
  { id: 'b', latitude: -1, longitude: 1 },
  { id: 'c', latitude: 1, longitude: 1 },
];

before(async () => {
  dom = new JSDOM('<!doctype html><html><body><div id="test"></div></body></html>');
  for (const [key, value] of Object.entries({ window: dom.window, document: dom.window.document,
    IS_REACT_ACT_ENVIRONMENT: true })) {
    previousGlobals.set(key, Object.getOwnPropertyDescriptor(globalThis, key));
    Object.defineProperty(globalThis, key, { value, writable: true, configurable: true });
  }
  ({ createRoot } = await import('react-dom/client'));
  dom.window.HTMLCanvasElement.prototype.getBoundingClientRect = () => ({ ...bounds, left: 0, top: 0 });
  dom.window.HTMLCanvasElement.prototype.getContext = () => new Proxy({}, {
    get: (context, method) => method === 'arc' ? (...values) => arcs.push(values)
      : method === 'fill' ? () => fills.push(context.fillStyle) : () => {},
    set: (context, property, value) => { context[property] = value; return true; },
  });
  dom.window.ResizeObserver = class {
    constructor(callback) { resize = () => callback([{ contentRect: bounds }]); }
    observe() {}
    disconnect() {}
  };
  vite = await createServer({
    root: fileURLToPath(new URL('..', import.meta.url)), configFile: false,
    esbuild: { jsx: 'automatic' }, optimizeDeps: { noDiscovery: true },
    server: { middlewareMode: true, hmr: false, ws: false, watch: null }, appType: 'custom',
  });
  NetworkCanvas = (await vite.ssrLoadModule('/src/components/NetworkCanvas.jsx')).default;
});

afterEach(async () => {
  if (root) await act(async () => root.unmount());
  root = null;
});

after(async () => {
  await vite?.close();
  dom.window.close();
  for (const [key, descriptor] of previousGlobals) {
    if (descriptor) Object.defineProperty(globalThis, key, descriptor);
    else delete globalThis[key];
  }
});

async function mount(props = {}) {
  arcs = [];
  fills = [];
  root = createRoot(document.querySelector('#test'));
  await act(async () => root.render(createElement(NetworkCanvas, { nodes, roads: [], ...props })));
  return document.querySelector('canvas');
}

test('canvas fits its displayed dimensions and keeps geometry proportional after resizing', async () => {
  bounds = { width: 400, height: 200 };
  const canvas = await mount();
  assert.equal(canvas.width, 400);
  assert.equal(canvas.height, 200);
  let [a, b, c] = arcs.slice(-3);
  assert.equal(b[0] - a[0], 130);
  assert.equal(b[1] - c[1], 130);

  bounds = { width: 200, height: 400 };
  await act(async () => resize());
  assert.equal(canvas.width, 200);
  assert.equal(canvas.height, 400);
  [a, b, c] = arcs.slice(-3);
  assert.equal(b[0] - a[0], 130);
  assert.equal(b[1] - c[1], 130);
});

test('canvas keeps a usable initial drawing size when layout has not been measured', async () => {
  bounds = { width: 0, height: 0 };
  const canvas = await mount();
  assert.equal(canvas.width, 1000);
  assert.equal(canvas.height, 650);
});

test('comparison visits remain visible without overwriting the selected search or current step', async () => {
  bounds = { width: 400, height: 200 };
  await mount({ comparisonVisited: new Set(['a', 'b', 'c']), visited: new Set(['b']), current: 'c' });
  const [comparison, selected, current] = fills.slice(-3);
  assert.notEqual(comparison, selected);
  assert.equal(comparison, '#7db8ad');
  assert.equal(selected, '#0B5C5E');
  assert.equal(current, '#B4410E');
});
