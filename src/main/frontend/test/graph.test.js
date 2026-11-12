import { test } from 'node:test';
import assert from 'node:assert/strict';
import { edgeKey, pathEdgeIndexes, projectNodes, visibleEdges } from '../src/utils/graph.js';

test('projection keeps geographic orientation and fits the viewport', () => {
  const points = projectNodes([
    { id: 'sw', latitude: 21, longitude: 105 },
    { id: 'ne', latitude: 21.002, longitude: 105.001 },
  ], 960, 640, 72);
  assert.ok(points[0].x < points[1].x);
  assert.ok(points[0].y > points[1].y);
  for (const point of points) {
    assert.ok(point.x >= 72 && point.x <= 888);
    assert.ok(point.y >= 72 && point.y <= 568);
  }
  assert.deepEqual(projectNodes([], 960, 640), []);
  assert.throws(() => projectNodes(points, 80, 80), RangeError);
});

test('coincident nodes remain finite and centered', () => {
  const [point] = projectNodes([{ latitude: 21, longitude: 105 }], 960, 640);
  assert.equal(point.x, 480);
  assert.equal(point.y, 320);
});

const edge = { source: 'a', target: 'b', weight: 1, roadName: 'Road' };
const reverse = { ...edge, source: 'b', target: 'a' };
const result = { sourceId: 'a', destinationId: 'b', weight: 1, roadName: 'Road' };

test('edge keys normalize API shapes and undirected orientation', () => {
  assert.equal(edgeKey(edge), edgeKey(result));
  assert.equal(edgeKey(edge, true), edgeKey(reverse, true));
  assert.notEqual(edgeKey(edge), edgeKey(reverse));
});

test('undirected rendering retains parallel roads and pairs self-loops', () => {
  const loop = { ...edge, source: 'a', target: 'a' };
  assert.equal(visibleEdges([edge, reverse, edge, reverse, loop, loop], true).length, 3);
  assert.equal(visibleEdges([edge, reverse], false).length, 2);
});

test('path highlight consumes identical parallel edges once per result edge', () => {
  assert.deepEqual([...pathEdgeIndexes([edge, edge, reverse], [result])], [0]);
  assert.deepEqual([...pathEdgeIndexes([edge, edge], [result, result])], [0, 1]);
  assert.deepEqual([...pathEdgeIndexes([reverse, edge], [result], true)], [0]);
  assert.equal(pathEdgeIndexes([edge], []).size, 0);
});
