import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';

const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });

test('map consumers share an in-flight request and reuse its successful result', async () => {
  const { loadMap } = await import('../src/utils/mapCache.js?success');
  let requests = 0;
  let complete;
  globalThis.fetch = (url, options) => {
    requests += 1;
    assert.equal(url, '/api/map');
    assert.equal(options.signal, undefined);
    return new Promise((resolve) => { complete = resolve; });
  };
  const first = loadMap();
  const second = loadMap();
  assert.equal(first, second);
  const map = { nodes: [{ id: 'a', latitude: 21, longitude: 105.8 }], roads: [] };
  complete(Response.json(map));
  assert.deepEqual(await first, map);
  assert.equal(await second, await first);
  assert.equal(await loadMap(), await first);
  assert.equal(requests, 1);
});

test('a failed map request is discarded so the next consumer can retry', async () => {
  const { loadMap } = await import('../src/utils/mapCache.js?retry');
  let requests = 0;
  globalThis.fetch = async () => {
    requests += 1;
    if (requests === 1) throw new TypeError('offline');
    return Response.json({ nodes: [], roads: [] });
  };
  await assert.rejects(loadMap(), { code: 'NETWORK_ERROR' });
  assert.deepEqual(await loadMap(), { nodes: [], roads: [] });
  assert.equal(requests, 2);
});
