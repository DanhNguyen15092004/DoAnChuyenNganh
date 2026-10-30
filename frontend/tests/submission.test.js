import test from 'node:test';
import assert from 'node:assert/strict';
import { AnswerSession } from '../src/answerSession.js';
import { submitAttempt } from '../src/submission.js';

const tick = () => new Promise(resolve => setImmediate(resolve));
function fixture() {
  let data = { status: 'IN_PROGRESS', questions: [{ id: 11 }, { id: 22 }], answers: [] };
  let before = true; let sends = 0; let expirations = 0; let confirmations = 0;
  const requests = []; const confirmationData = [];
  const store = new AnswerSession({
    save: (id, body) => new Promise((resolve, reject) => requests.push({ id, body, resolve, reject })),
    read: async () => structuredClone(data), canWrite: () => before && data.status === 'IN_PROGRESS',
    onAttempt: () => {}, changed: () => {},
  });
  store.merge(data);
  const options = {
    session: store, beforeDeadline: () => before,
    confirm: value => { confirmations++; confirmationData.push(value); return true; },
    send: async () => { sends++; data = { ...data, status: 'SUBMITTED' }; },
    expire: async () => { expirations++; data = { ...data, status: 'TIMED_OUT' }; },
  };
  return { store, requests, options, confirmationData,
    counts: () => ({ sends, expirations, confirmations }), deadline: () => { before = false; },
    server: update => { data = { ...data, ...update }; },
    acknowledge: index => {
      const request = requests[index];
      const answer = { questionId: request.id, selectedOptionId: request.body.selectedOptionId,
        revision: request.body.expectedRevision + 1, savedAt: '2030-01-01T00:00:00Z' };
      data.answers = [...data.answers.filter(a => a.questionId !== answer.questionId), answer]; request.resolve(answer);
    },
  };
}
test('submit locks choices, drains current and queued saves, GET verifies, then confirms persisted counts', async () => {
  const f = fixture(); f.store.choose(11, 101); f.store.choose(11, 102);
  const pending = submitAttempt(f.options);
  f.store.choose(22, 201); assert.equal(f.store.answers[22].desired, null);
  assert.equal(f.counts().sends, 0); f.acknowledge(0); await tick();
  assert.equal(f.requests.length, 2); assert.equal(f.counts().confirmations, 0);
  f.acknowledge(1); assert.equal(await pending, 'finished');
  assert.deepEqual(f.counts(), { sends: 1, expirations: 0, confirmations: 1 });
  assert.equal(f.confirmationData[0].answered, 1); assert.equal(f.confirmationData[0].unanswered, 1);
  assert.equal(f.store.editingLocked, false);
});
test('cancelling confirmation sends nothing and unlocks editing', async () => {
  const f = fixture(); f.options.confirm = () => false;
  assert.equal(await submitAttempt(f.options), 'cancelled'); assert.equal(f.counts().sends, 0);
  f.store.choose(11, 101); assert.equal(f.requests.length, 1); f.acknowledge(0); await tick();
});
test('failed or conflicting save blocks submit with actual question numbers even after synchronization', async () => {
  for (const conflict of [false, true]) {
    const f = fixture(); f.store.choose(11, 101); const pending = submitAttempt(f.options);
    const error = Object.assign(new Error('Save failed'), conflict ? { problem: { code: 'ANSWER_REVISION_CONFLICT' } } : {});
    const rejected = assert.rejects(pending, /câu: 1/);
    f.requests[0].reject(error); await rejected;
    assert.equal(f.counts().sends, 0); assert.equal(f.counts().confirmations, 0); assert.equal(f.store.editingLocked, false);
  }
});
test('ambiguous save which actually committed still requires explicit resolution before submitting', async () => {
  const f = fixture(); f.store.choose(11, 101);
  f.server({ answers: [{ questionId: 11, selectedOptionId: 101, revision: 1, savedAt: '2030-01-01T00:00:00Z' }] });
  f.requests[0].reject(new Error('Response lost')); await tick();
  await assert.rejects(submitAttempt(f.options), /câu: 1/); assert.equal(f.counts().sends, 0);
  await f.store.retry(11); assert.equal(f.requests.length, 1);
  assert.equal(await submitAttempt(f.options), 'finished'); assert.equal(f.counts().sends, 1);
});
test('deadline reached while draining or during confirmation uses timeout without submit', async () => {
  const f = fixture(); f.store.choose(11, 101); const pending = submitAttempt(f.options);
  f.deadline(); f.acknowledge(0);
  assert.equal(await pending, 'timeout'); assert.deepEqual(f.counts(), { sends: 0, expirations: 1, confirmations: 0 });
  const g = fixture(); g.options.confirm = () => { g.deadline(); return true; };
  assert.equal(await submitAttempt(g.options), 'timeout'); assert.equal(g.counts().sends, 0); assert.equal(g.counts().expirations, 1);
});
test('lost submit response synchronizes a committed final state without resending submit', async () => {
  const f = fixture(); let calls = 0;
  f.options.send = async () => { calls++; f.server({ status: 'SUBMITTED' }); throw new Error('Response lost'); };
  assert.equal(await submitAttempt(f.options), 'finished'); assert.equal(calls, 1);
});
test('unconfirmed submit never claims success when GET fails or still reports in progress', async () => {
  for (const cannotRead of [false, true]) {
    const f = fixture(); let calls = 0;
    f.options.send = async () => {
      calls++;
      if (cannotRead) f.store.read = async () => { throw new Error('Offline'); };
      throw new Error('Uncertain response');
    };
    await assert.rejects(submitAttempt(f.options), cannotRead ? /Chưa xác nhận/ : /vẫn báo đang làm bài/);
    assert.equal(calls, 1); assert.equal(f.store.editingLocked, false);
  }
});
test('already finalized state opens result without another submit, timeout or confirmation', async () => {
  const f = fixture(); f.server({ status: 'TIMED_OUT', questions: [] });
  assert.equal(await submitAttempt(f.options), 'finished');
  assert.deepEqual(f.counts(), { sends: 0, expirations: 0, confirmations: 0 });
});
test('closing the page wakes a preparation waiting for an answer and prevents submission', async () => {
  const f = fixture(); f.store.choose(11, 101);
  const pending = submitAttempt(f.options); const rejected = assert.rejects(pending, /Trang làm bài đã đóng/);
  f.store.dispose(); await rejected; f.acknowledge(0); await tick(); assert.equal(f.counts().sends, 0);
});
