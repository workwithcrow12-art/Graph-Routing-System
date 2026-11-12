import { afterEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { api, ApiError } from '../src/api.js';

const originalFetch = globalThis.fetch;
afterEach(() => { globalThis.fetch = originalFetch; });

test('search encodes Vietnamese and pagination; propagates cancellation signal', async () => {
  const controller = new AbortController();
  globalThis.fetch = async (url, options) => {
    const query = new URL(url, 'http://localhost').searchParams;
    assert.equal(query.get('q'), 'Nguyễn Trãi & phố');
    assert.equal(query.get('mode'), 'walking');
    assert.equal(query.get('offset'), '10');
    assert.equal(options.signal, controller.signal);
    assert.equal(options.method, 'GET');
    return Response.json({ places: [], total: 0 });
  };
  assert.deepEqual(await api.searchPlaces('Nguyễn Trãi & phố', {
    mode: 'walking', offset: 10, signal: controller.signal,
  }), { places: [], total: 0 });
});

test('edits send JSON and retain expectedRevision', async () => {
  const body = { operation: 'reset', expectedRevision: 3 };
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/analysis/graph/edit');
    assert.equal(options.method, 'POST');
    assert.equal(options.headers['Content-Type'], 'application/json');
    assert.deepEqual(JSON.parse(options.body), body);
    return Response.json({ revision: 4 });
  };
  assert.equal((await api.editAnalysisGraph(body)).revision, 4);
});

test('quota errors retain backend code and HTTP status', async () => {
  globalThis.fetch = async () => Response.json({ error: {
    code: 'ROUTE_LIMIT_EXCEEDED', message: 'Reduce alternatives',
  } }, { status: 422 });
  await assert.rejects(api.route({ origin: 'a', destination: 'b' }), (error) =>
    error instanceof ApiError && error.status === 422 && error.code === 'ROUTE_LIMIT_EXCEEDED');
});

test('HTML transport errors are reported as invalid responses', async () => {
  globalThis.fetch = async () => new Response('<h1>Bad Request</h1>', { status: 400 });
  await assert.rejects(api.health(), (error) =>
    error instanceof ApiError && error.status === 400 && error.code === 'INVALID_RESPONSE');
});

test('network errors and cancellation remain distinct', async () => {
  globalThis.fetch = async () => { throw new TypeError('offline'); };
  await assert.rejects(api.health(), { code: 'NETWORK_ERROR' });
  const aborted = new DOMException('cancelled', 'AbortError');
  globalThis.fetch = async () => { throw aborted; };
  await assert.rejects(api.health(), (error) => error === aborted);
});
