import { test } from 'node:test';
import assert from 'node:assert/strict';
import { remainingMilliseconds, formatRemaining } from '../src/attemptTime.js';

const attempt = { availability: 'ACTIVE', serverTime: '2030-01-01T00:00:00Z', deadlineAt: '2030-01-01T00:10:00Z' };
test('countdown uses server budget and total elapsed monotonic time, including skipped ticks', () => {
  assert.equal(remainingMilliseconds(attempt, 1000, 1000), 600000);
  assert.equal(remainingMilliseconds(attempt, 1000, 124456), 476544);
  assert.equal(remainingMilliseconds(attempt, 1000, 700000), 0);
});
test('new server sample reanchors countdown after a suspended tab without resetting the deadline', () => {
  const refreshed = { ...attempt, serverTime: '2030-01-01T00:09:00Z' };
  assert.equal(remainingMilliseconds(refreshed, 5000, 5000), 60000);
  assert.equal(remainingMilliseconds(refreshed, 5000, 15000), 50000);
  assert.equal(remainingMilliseconds({ ...refreshed, availability: 'EXPIRED' }, 5000, 5000), 0);
});
test('remaining display rounds only for presentation and supports durations above one hour', () => {
  assert.equal(formatRemaining(0), '00:00:00');
  assert.equal(formatRemaining(1), '00:00:01');
  assert.equal(formatRemaining(3661000), '01:01:01');
});
