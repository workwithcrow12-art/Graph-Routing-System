import { test } from 'node:test';
import assert from 'node:assert/strict';
import { roadFeatures, routeFeatures, geometryBounds, linePosition } from '../src/utils/map-view.js';

test('map roads keep coordinate order and remove reverse copies without removing different shapes', () => {
  const geometry = [{ latitude: 21, longitude: 105 }, { latitude: 21.01, longitude: 105.01 }];
  const result = roadFeatures({ places: [], roads: [
    { id: 'a', name: 'Phố Quan Nhân', sourceWayId: 'way', geometry, speedsKph: { DRIVING: 40 } },
    { id: 'b', name: 'Phố Quan Nhân', sourceWayId: 'way', geometry: [...geometry].reverse(), speedsKph: { DRIVING: 40 } },
    { id: 'c', name: 'Phố Quan Nhân', sourceWayId: 'way', geometry: [geometry[0], { latitude: 21.005, longitude: 105.007 }, geometry[1]], speedsKph: { WALKING: 5 } },
  ] });
  assert.equal(result.features.length, 2);
  assert.deepEqual(result.features[0].geometry.coordinates, [[105, 21], [105.01, 21.01]]);
  assert.equal(result.features[0].properties.name, 'Phố Quan Nhân');
});

test('map road endpoints remain usable when the optional shape is missing', () => {
  const result = roadFeatures({ places: [{ id: 'a', longitude: 105, latitude: 21 }, { id: 'b', longitude: 106, latitude: 22 }],
    roads: [{ source: 'a', target: 'b', name: 'A' }, { source: 'missing', target: 'b', name: 'B' }] });
  assert.deepEqual(result.features.map((feature) => feature.geometry.coordinates), [[[105, 21], [106, 22]]]);
});

test('routes with a duplicate node sequence are not drawn as alternatives', () => {
  const route = { geometry: { type: 'LineString', coordinates: [[105, 21], [106, 22]] }, durationSeconds: 208, distanceMeters: 2036 };
  const result = routeFeatures([route, { ...route, sameNodeSequenceAsPrimary: true }, { ...route, durationSeconds: 212 }], 2);
  assert.deepEqual(result.features.map((feature) => feature.properties), [
    { index: 0, selected: false }, { index: 2, selected: true },
  ]);
});

test('route animation moves by physical distance instead of array index', () => {
  const point = linePosition([[0, 0], [1, 0], [4, 0]], 0.5);
  assert.ok(Math.abs(point[0] - 2) < 0.00001);
  assert.equal(point[1], 0);
  assert.deepEqual(linePosition([[105, 21], [105, 21]], 0.8), [105, 21]);
});

test('map bounds include intermediate road geometry outside endpoint coordinates', () => {
  assert.deepEqual(geometryBounds([[105, 21], [104, 22], [106, 20]]), [[104, 20], [106, 22]]);
  assert.equal(geometryBounds([]), null);
});
