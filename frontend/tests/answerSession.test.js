import test from 'node:test';
import assert from 'node:assert/strict';
import { AnswerSession, answerCounts, answerStatus } from '../src/answerSession.js';

const tick = () => new Promise(resolve => setImmediate(resolve));
function fixture() {
  const requests = []; const events = []; let writable = true;
  let data = { questions: [{ id: 11 }, { id: 22 }], answers: [] };
  const store = new AnswerSession({
    save: (id, body) => new Promise((resolve, reject) => {
      events.push('PUT'); requests.push({ id, body, resolve, reject });
    }),
    read: async () => { events.push('GET'); return structuredClone(data); },
    canWrite: () => writable, onAttempt: () => {}, changed: () => {},
  });
  store.merge(data);
  return { store, requests, events, writable: value => { writable = value; },
    server: value => { data = { ...data, answers: value }; },
    confirm: (index, revision) => {
      const r = requests[index];
      const answer = { questionId: r.id, selectedOptionId: r.body.selectedOptionId, revision, savedAt: '2030-01-01T00:00:00Z' };
      data.answers = [...data.answers.filter(a => a.questionId !== r.id), answer]; r.resolve({ ...answer, serverTime: answer.savedAt });
    },
  };
}
test('rapid select/change/clear shares navigation counts, queues only latest choice and preserves revision', async () => {
  const f = fixture(); const s = f.store;
  s.choose(11, 101); s.choose(11, 102); s.choose(11, null);
  assert.equal(f.requests.length, 1);
  assert.deepEqual(answerCounts(s.answers), { total: 2, selected: 0, unanswered: 2, unsaved: 1 });
  assert.equal(answerStatus(s.answers[11]), 'saving');
  f.confirm(0, 1); await tick();
  assert.equal(s.answers[11].desired, null); assert.equal(f.requests.length, 2);
  assert.deepEqual(f.requests[1].body, { selectedOptionId: null, expectedRevision: 1 });
  assert.equal(answerCounts(s.answers).unsaved, 1);
  f.confirm(1, 2); await tick();
  assert.equal(answerStatus(s.answers[11]), 'unanswered'); assert.equal(s.answers[11].revision, 2);
  assert.equal(answerCounts(s.answers).unsaved, 0);
});
test('older response never changes the newest radio choice and saved requires its acknowledgement', async () => {
  const f = fixture(); f.store.choose(11, 101); f.store.choose(11, 102);
  f.confirm(0, 1); await tick();
  assert.equal(f.store.answers[11].desired, 102);
  assert.equal(f.store.answers[11].confirmed, 101);
  assert.equal(answerStatus(f.store.answers[11]), 'saving');
  f.confirm(1, 2); await tick();
  assert.equal(answerStatus(f.store.answers[11]), 'saved');
});
test('uncertain response keeps intent, GET synchronizes, manual retry does not duplicate an already committed PUT', async () => {
  const f = fixture(); f.store.choose(11, 101);
  f.server([{ questionId: 11, selectedOptionId: 101, revision: 1, savedAt: '2030-01-01T00:00:00Z' }]);
  f.requests[0].reject(new Error('Mất kết nối')); await tick();
  assert.equal(f.store.answers[11].desired, 101);
  assert.equal(answerStatus(f.store.answers[11]), 'error'); assert.equal(answerCounts(f.store.answers).unsaved, 1);
  assert.deepEqual(f.events, ['PUT', 'GET']);
  await f.store.retry(11);
  assert.deepEqual(f.events, ['PUT', 'GET', 'GET']); assert.equal(answerStatus(f.store.answers[11]), 'saved');
});
test('failed save retains choice and only retries after manual action and a fresh GET', async () => {
  const f = fixture(); f.store.choose(11, 101);
  f.requests[0].reject(new Error('Offline')); await tick();
  f.store.choose(11, 102); assert.equal(f.store.answers[11].desired, 101);
  const retry = f.store.retry(11); await tick();
  assert.deepEqual(f.events, ['PUT', 'GET', 'GET', 'PUT']);
  assert.equal(f.requests[1].body.expectedRevision, 0);
  f.confirm(1, 1); await retry; assert.equal(answerStatus(f.store.answers[11]), 'saved');
});
test('conflict synchronizes without overwriting, asks user to choose again with the actual revision', async () => {
  const f = fixture(); f.store.choose(11, 101);
  f.server([{ questionId: 11, selectedOptionId: 102, revision: 4, savedAt: '2030-01-01T00:00:00Z' }]);
  const conflict = Object.assign(new Error('Xung đột'), { problem: { code: 'ANSWER_REVISION_CONFLICT' } });
  f.requests[0].reject(conflict); await tick();
  assert.equal(answerStatus(f.store.answers[11]), 'conflict'); assert.equal(f.store.answers[11].confirmed, 102);
  assert.equal(f.store.answers[11].desired, 101); assert.equal(f.requests.length, 1);
  await f.store.retry(11);
  assert.equal(f.store.answers[11].desired, 102); assert.equal(f.requests.length, 1);
  f.store.choose(11, 101); assert.deepEqual(f.requests[1].body, { selectedOptionId: 101, expectedRevision: 4 });
  f.confirm(1, 5); await tick();
});
test('reload reconstructs saved and cleared choices from server and ignores an older read revision', async () => {
  const f = fixture();
  f.server([{ questionId: 11, selectedOptionId: 101, revision: 3, savedAt: '2030-01-01T00:00:00Z' },
    { questionId: 22, selectedOptionId: null, revision: 2, savedAt: '2030-01-01T00:00:00Z' }]);
  await f.store.synchronize();
  assert.deepEqual(answerCounts(f.store.answers), { total: 2, selected: 1, unanswered: 1, unsaved: 0 });
  f.store.merge({ questions: [{ id: 11 }], answers: [{ questionId: 11, selectedOptionId: null, revision: 1 }] });
  assert.equal(f.store.answers[11].desired, 101); assert.equal(f.store.answers[22].revision, 2);
});
test('failed conflict synchronization still requires reselection on the next retry and never writes stale intent', async () => {
  const f = fixture(); f.store.choose(11, 101);
  f.server([{ questionId: 11, selectedOptionId: 102, revision: 2, savedAt: '2030-01-01T00:00:00Z' }]);
  f.requests[0].reject(Object.assign(new Error('Conflict'), { problem: { code: 'ANSWER_REVISION_CONFLICT' } })); await tick();
  const read = f.store.read; f.store.read = async () => { throw new Error('Offline'); };
  await f.store.retry(11); assert.equal(answerStatus(f.store.answers[11]), 'conflict');
  f.store.read = read; await f.store.retry(11);
  assert.equal(f.requests.length, 1); assert.equal(f.store.answers[11].desired, 102);
});
test('each question has its own single flight, with no queued writes after deadline', async () => {
  const f = fixture(); f.store.choose(11, 101); f.store.choose(22, 201); f.store.choose(11, 102);
  assert.equal(f.requests.length, 2); f.writable(false);
  f.confirm(0, 1); f.confirm(1, 1); await tick();
  assert.equal(f.requests.length, 2);
  assert.equal(f.store.answers[11].desired, 102); assert.equal(answerStatus(f.store.answers[11]), 'error');
  assert.equal(answerCounts(f.store.answers).unsaved, 1);
});
test('conflict and an unconfirmed clear count as unsaved even with no currently selected option', () => {
  const rows = { 1: { desired: null, confirmed: 100, saving: true },
    2: { desired: null, confirmed: null, issue: 'conflict' },
    3: { desired: 300, confirmed: 300 }, 4: { desired: null, confirmed: null } };
  assert.deepEqual(answerCounts(rows), { total: 4, selected: 1, unanswered: 3, unsaved: 2 });
});
test('manual synchronization after deadline does not mark pending intent as saving or send another PUT', async () => {
  const f = fixture(); f.store.choose(11, 101);
  f.requests[0].reject(new Error('Offline')); await tick(); f.writable(false);
  await f.store.retry(11);
  assert.equal(f.requests.length, 1); assert.equal(answerStatus(f.store.answers[11]), 'error');
  assert.equal(f.store.answers[11].desired, 101); assert.equal(answerCounts(f.store.answers).unsaved, 1);
});
test('disposing a page prevents old responses from sending queued writes', async () => {
  const f = fixture(); f.store.choose(11, 101); f.store.choose(11, 102); f.store.dispose();
  f.confirm(0, 1); await tick(); assert.equal(f.requests.length, 1);
});
