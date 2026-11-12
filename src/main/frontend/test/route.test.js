import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatDistance, formatDuration, mergeDirections, visibleRoutes } from '../src/utils/route.js';

test('route durations preserve seconds so close alternatives stay distinguishable', () => {
  for (const [seconds, expected] of [
    [0, '0 giây'], [59, '59 giây'], [60, '1 phút'],
    [208, '3 phút 28 giây'], [212, '3 phút 32 giây'],
    [3600, '1 giờ'], [3661, '1 giờ 1 phút 1 giây'],
  ]) assert.equal(formatDuration(seconds), expected);
});

test('route distances use Vietnamese decimals and metres for short steps', () => {
  for (const [meters, expected] of [
    [0, '0 m'], [32.6, '33 m'], [999, '999 m'],
    [1000, '1,00 km'], [2036, '2,04 km'], [12345, '12,35 km'],
  ]) assert.equal(formatDistance(meters), expected);
});

test('missing route metrics stay unavailable instead of showing fabricated zeroes', () => {
  for (const value of [undefined, null, NaN, Infinity]) {
    assert.equal(formatDuration(value), '—');
    assert.equal(formatDistance(value), '—');
  }
});

test('duplicate alternatives are hidden while the primary and distinct routes remain ordered', () => {
  const routes = [
    { id: 'primary', sameNodeSequenceAsPrimary: true },
    { id: 'duplicate', sameNodeSequenceAsPrimary: true },
    { id: 'alternative', sameNodeSequenceAsPrimary: false },
    { id: 'legacy' },
  ];
  assert.deepEqual(visibleRoutes(routes).map((route) => route.id), ['primary', 'alternative', 'legacy']);
  assert.equal(routes.length, 4);
  assert.deepEqual(visibleRoutes([]), []);
});

test('consecutive continue steps on the same named road merge without changing source data', () => {
  const directions = [
    { maneuver: 'DEPART', roadName: 'Quan Nhân', from: 'a', to: 'b', distanceMeters: 20, durationSeconds: 4 },
    { maneuver: 'CONTINUE', roadName: 'Quan Nhân', from: 'b', to: 'c', distanceMeters: 100, durationSeconds: 20 },
    { maneuver: 'CONTINUE', roadName: 'Quan Nhân', from: 'c', to: 'd', distanceMeters: 150, durationSeconds: 30 },
    { maneuver: 'TURN_LEFT', roadName: 'Quan Nhân', from: 'd', to: 'e', distanceMeters: 40, durationSeconds: 8 },
    { maneuver: 'CONTINUE', roadName: 'Nguyễn Trãi', from: 'e', to: 'f', distanceMeters: 70, durationSeconds: 14 },
    { maneuver: 'ARRIVE', roadName: '', from: 'f', to: 'f', distanceMeters: 0, durationSeconds: 0 },
  ];
  const original = structuredClone(directions);
  const merged = mergeDirections(directions);
  assert.equal(merged.length, 5);
  assert.deepEqual(merged[1], {
    maneuver: 'CONTINUE', roadName: 'Quan Nhân', from: 'b', to: 'd', distanceMeters: 250, durationSeconds: 50,
  });
  assert.equal(merged[0].maneuver, 'DEPART');
  assert.equal(merged[2].maneuver, 'TURN_LEFT');
  assert.deepEqual(directions, original);
});

test('unnamed or different roads and turns keep distinct direction steps', () => {
  const directions = [
    { maneuver: 'CONTINUE', roadName: '', distanceMeters: 10 },
    { maneuver: 'CONTINUE', roadName: '', distanceMeters: 20 },
    { maneuver: 'CONTINUE', roadName: 'Quan Nhân', distanceMeters: 30 },
    { maneuver: 'CONTINUE', roadName: 'Nguyễn Trãi', distanceMeters: 40 },
    { maneuver: 'TURN_RIGHT', roadName: 'Nguyễn Trãi', distanceMeters: 50 },
  ];
  assert.deepEqual(mergeDirections(directions), directions);
  assert.deepEqual(mergeDirections([]), []);
});

test('merging steps without optional durations does not create an invalid duration', () => {
  for (const [first, second] of [[undefined, undefined], [20, undefined], [undefined, 30]]) {
    const merged = mergeDirections([
      { maneuver: 'CONTINUE', roadName: 'Quan Nhân', distanceMeters: 10, durationSeconds: first },
      { maneuver: 'CONTINUE', roadName: 'Quan Nhân', distanceMeters: 20, durationSeconds: second },
    ]);
    assert.equal(merged[0].distanceMeters, 30);
    assert.equal(merged[0].durationSeconds, undefined);
  }
});
