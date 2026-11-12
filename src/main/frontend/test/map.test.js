import { test } from 'node:test';
import assert from 'node:assert/strict';
import { directionText, filterRoads, mapProjection, trafficBody } from '../src/utils/map.js';

test('map projection round-trips geographic coordinates including degenerate graphs', () => {
  for (const nodes of [[], [{ latitude: 21, longitude: 105 }],
    [{ latitude: 20.99, longitude: 105.79 }, { latitude: 21.01, longitude: 105.83 }]]) {
    const projection = mapProjection(nodes);
    const [x, y] = projection.point(21, 105.81);
    const coordinate = projection.coordinate(x, y);
    assert.ok(Number.isFinite(x) && Number.isFinite(y));
    assert.ok(Math.abs(coordinate.lat - 21) < 1e-10);
    assert.ok(Math.abs(coordinate.lng - 105.81) < 1e-10);
  }
});

const road = { id: 'arc:7', source: 'a', target: 'b', sourceWayId: 'way:3', speedsKph: { DRIVING: 30 } };
test('directions use Vietnamese and keep missing source names explicit', () => {
  assert.equal(directionText({ maneuver: 'TURN_LEFT', roadName: 'Nguyễn Trãi' }), 'Rẽ trái vào Nguyễn Trãi');
  assert.equal(directionText({ maneuver: 'DEPART', roadName: '' }), 'Xuất phát trên đường không tên');
  assert.equal(directionText({ maneuver: 'ARRIVE' }, 'Điểm B'), 'Đã đến Điểm B');
  assert.equal(directionText({ maneuver: 'ARRIVE' }), 'Đến nơi');
  assert.equal(directionText({ maneuver: 'NEW', roadName: 'Quan Nhân', instruction: 'Future instruction' }), 'Tiếp tục trên Quan Nhân');
  assert.equal(directionText({ maneuver: 'CONTINUE', roadName: '   ' }), 'Tiếp tục trên đường không tên');
});
test('traffic filtering is accent-insensitive, bounded, and retains the selected arc', () => {
  const roads = Array.from({ length: 200 }, (_, index) => ({ ...road, id: `arc:${index}`, name: 'Đường Nguyễn Trãi' }));
  const result = filterRoads(roads, 'nguyen trai', 'arc:199');
  assert.equal(result.total, 200);
  assert.equal(result.visible.length, 101);
  assert.equal(result.visible[0].id, 'arc:199');
  assert.equal(filterRoads(roads, 'arc:199', '').visible[0].id, 'arc:199');
});
test('traffic submission targets exactly one directional arc and expires in ten minutes', () => {
  const now = new Date('2026-10-25T00:00:00Z');
  const body = trafficBody([road], 'arc:7', 'driving', '8', false, 10, now);
  assert.equal(body.updates.length, 1);
  assert.equal(body.updates[0].segmentId, 'arc:7');
  assert.equal(body.updates[0].from, 'a');
  assert.equal(body.updates[0].to, 'b');
  assert.equal(body.updates[0].speedKph, 8);
  assert.equal(body.expiresAt, '2026-10-25T00:10:00.000Z');
});

test('traffic validation rejects unsupported modes, unknown segments and invalid speeds', () => {
  assert.throws(() => trafficBody([road], 'missing', 'driving', 10, false));
  assert.throws(() => trafficBody([road], 'arc:7', 'walking', 10, false));
  for (const speed of [0, -1, 201, NaN, Infinity]) assert.throws(() => trafficBody([road], 'arc:7', 'driving', speed, false));
  assert.equal(trafficBody([road], 'arc:7', 'driving', 0, true).updates[0].speedKph, null);
});
