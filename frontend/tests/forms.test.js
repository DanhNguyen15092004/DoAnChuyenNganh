import assert from 'node:assert/strict';
import { test } from 'node:test';
import { metadataError, questionPayload, questionsError, toInstant, toLocalInput } from '../src/forms.js';

test('local datetime converts through timezone and round-trips, without attaching Z', () => {
  const original = '2030-06-01T08:35:12.123Z';
  assert.equal(toInstant(toLocalInput(original)), original);
  assert.throws(() => toInstant(''));
});
test('question payload strips server IDs and rebuilds unique positions after reordering', () => {
  const raw = [{ id: 12, content: 'Câu hỏi', position: 9, options: [
 { id: 7, content: 'B', position: 4, correct: false },
 { id: 8, content: 'A', position: 8, correct: true },
  ] }];
  assert.deepEqual(questionPayload(raw), [{ content: 'Câu hỏi', position: 1, options: [
 { content: 'B', position: 1, correct: false }, { content: 'A', position: 2, correct: true },
  ] }]);
  assert.equal(questionsError(raw), null);
  assert.ok(questionsError([{ ...raw[0], options: raw[0].options.map(o => ({ ...o, correct: false })) }]));
  assert.ok(questionsError([{ ...raw[0], options: raw[0].options.map(o => ({ ...o, correct: true })) }]));
});
test('metadata requires a window at least as long as duration', () => {
  const metadata = { title: 'Thi', description: '', durationMinutes: 30, opensAt: '2030-01-01T08:00:00Z', closesAt: '2030-01-01T08:30:00Z' };
  assert.equal(metadataError(metadata), null);
  assert.ok(metadataError({ ...metadata, durationMinutes: 31 }));
  assert.ok(metadataError({ ...metadata, durationMinutes: 1.5 }));
  assert.ok(metadataError({ ...metadata, title: '  ' }));
});
