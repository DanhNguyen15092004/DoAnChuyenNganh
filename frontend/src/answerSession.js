// One source of truth for radios, synchronization, navigation and counts. Memory only.
export function answerStatus(answer) {
  if (answer.saving) return 'saving';
  if (answer.issue === 'conflict') return 'conflict';
  if (answer.issue) return 'error';
  if (answer.desired !== answer.confirmed) return 'saving';
  return answer.desired === null ? 'unanswered' : 'saved';
}
export function answerCounts(answers) {
  const list = Object.values(answers);
  const selected = list.filter(a => a.desired !== null).length;
  return { total: list.length, selected, unanswered: list.length - selected,
    unsaved: list.filter(a => ['saving', 'error', 'conflict'].includes(answerStatus(a))).length };
}
export const answerLabels = {
  unanswered: '○ Chưa trả lời', saving: '↻ Đang lưu', saved: '✓ Đã lưu',
  error: '! Lưu thất bại / chưa xác nhận', conflict: '⚠ Xung đột — cần xử lý',
};

export class AnswerSession {
  constructor({ save, read, canWrite, onAttempt, changed }) {
    this.save = save; this.read = read; this.canWrite = canWrite;
    this.onAttempt = onAttempt; this.changed = changed;
    this.answers = {}; this.active = true; this.editingLocked = false; this.waiters = new Set();
  }
  emit() {
    if (this.active) this.changed({ ...this.answers });
    for (const wake of this.waiters) wake();
    this.waiters.clear();
  }
  dispose() { this.active = false; this.emit(); }
  resumeEditing() { this.editingLocked = false; this.emit(); }
  async prepareSubmission() {
    this.editingLocked = true; this.emit();
    while (this.active && Object.values(this.answers).some(a => a.saving)) {
      await new Promise(resolve => this.waiters.add(resolve));
    }
    if (!this.active) throw new Error('Trang làm bài đã đóng.');
    // GET also resolves an ambiguous write before the user can decide about submission.
    const data = await this.synchronize();
    if (data.status !== 'IN_PROGRESS') return { data, answered: 0, unanswered: 0 };
    const unresolved = data.questions.flatMap((q, index) => {
      const a = this.answers[q.id];
      return !a || a.saving || a.issue || a.desired !== a.confirmed ? [index + 1] : [];
    });
    if (unresolved.length) throw new Error(`Chưa thể nộp. Hãy xử lý lưu đáp án tại câu: ${unresolved.join(', ')}.`);
    const answered = data.answers.filter(a => a.selectedOptionId !== null).length;
    return { data, answered, unanswered: data.questions.length - answered };
  }
  merge(attempt) {
    const stored = new Map(attempt.answers.map(a => [a.questionId, a]));
    for (const question of attempt.questions) {
      const server = stored.get(question.id) || { selectedOptionId: null, revision: 0, savedAt: null };
      const old = this.answers[question.id];
      if (!old) {
        this.answers[question.id] = { desired: server.selectedOptionId, confirmed: server.selectedOptionId,
          revision: server.revision, savedAt: server.savedAt, saving: false, issue: null, message: '' };
      } else if (!old.saving && server.revision >= old.revision) {
        const wasClean = !old.issue && old.desired === old.confirmed;
        const advanced = server.revision > old.revision;
        old.confirmed = server.selectedOptionId; old.revision = server.revision; old.savedAt = server.savedAt;
        if (wasClean) old.desired = old.confirmed;
        else if (advanced && !old.issue) {
          old.issue = 'conflict'; old.message = 'Đáp án trên máy chủ đã thay đổi. Hãy kiểm tra và chủ động chọn lại.';
        }
      }
    }
    this.emit();
  }
  choose(id, choice) {
    const a = this.answers[id];
    if (!a || !this.canWrite() || !this.active || this.editingLocked) return;
    // After uncertain/conflicting writes, synchronize before accepting another write.
    if (a.issue) return;
    a.desired = choice; this.emit();
    void this.flush(id);
  }
  async flush(id) {
    const a = this.answers[id];
    if (!this.active || a.saving || a.issue || a.desired === a.confirmed) return;
    if (!this.canWrite()) {
      a.issue = 'error'; a.message = 'Chưa lưu lựa chọn này. Không thể ghi khi hết giờ hoặc chưa xác minh được lượt thi.';
      this.emit(); return;
    }
    const choice = a.desired; const expectedRevision = a.revision;
    a.saving = true; this.emit();
    try {
      const result = await this.save(id, { selectedOptionId: choice, expectedRevision });
      if (!this.active) return;
      if (result.questionId !== Number(id) || result.revision !== expectedRevision + 1 || result.selectedOptionId !== choice) {
        throw new Error('Phản hồi lưu chưa xác nhận đúng lựa chọn. Cần đồng bộ từ máy chủ.');
      }
      a.confirmed = result.selectedOptionId; a.revision = result.revision; a.savedAt = result.savedAt;
      a.saving = false; this.emit();
      // Desired stays untouched: an older acknowledgement never rewinds a newer choice.
      void this.flush(id);
    } catch (error) {
      if (!this.active) return;
      a.saving = false;
      a.issue = error.problem?.code === 'ANSWER_REVISION_CONFLICT' ? 'conflict' : 'error';
      a.message = error.message || 'Chưa xác nhận được kết quả lưu.';
      this.emit();
      // Read only. No mutation retry after a network failure, timeout or conflict.
      await this.synchronize().catch(() => {});
    }
  }
  async synchronize() {
    const data = await this.read();
    if (this.active) { this.merge(data); this.onAttempt(data); }
    return data;
  }
  async retry(id) {
    const a = this.answers[id];
    if (!a || a.saving || !this.active || this.editingLocked) return;
    const conflicted = a.issue === 'conflict';
    a.saving = true; this.emit();
    try {
      // Even if the previous PUT committed but its response was lost, this GET sees it.
      const data = await this.read();
      if (!this.active) return;
      const server = data.answers.find(x => x.questionId === Number(id)) || { selectedOptionId: null, revision: 0, savedAt: null };
      a.confirmed = server.selectedOptionId; a.revision = server.revision; a.savedAt = server.savedAt;
      a.saving = false;
      this.onAttempt(data);
      if (conflicted || a.issue === 'conflict') {
        a.desired = a.confirmed; a.issue = null; a.message = '';
        // Explicit user synchronization discards queued intent; user must select again.
        this.emit(); return;
      }
      a.issue = null; a.message = ''; this.emit();
      await this.flush(id); // The shared guard marks unsaved expired intent without sending a PUT.
    } catch (error) {
      if (!this.active) return;
      a.saving = false; a.issue = conflicted ? 'conflict' : 'error'; a.message = error.message; this.emit();
    }
  }
}
