/** No mutation retries. Navigation is decided by the caller, once a final state is confirmed. */
export async function submitAttempt({ session, confirm, beforeDeadline, send, expire, isCurrent = () => true }) {
  try {
    const prepared = await session.prepareSubmission();
    if (!isCurrent() || !session.active) return 'cancelled';
    if (prepared.data.status !== 'IN_PROGRESS') return 'finished';
    if (!beforeDeadline()) { await expire(); return 'timeout'; }
    if (!confirm(prepared)) return 'cancelled';
    if (!isCurrent() || !session.active) return 'cancelled';
    if (!beforeDeadline()) { await expire(); return 'timeout'; }
    try { await send(); return 'finished'; }
    catch (failure) {
      let actual;
      try { actual = await session.synchronize(); }
      catch { throw new Error(`Chưa xác nhận được việc nộp bài. Hãy kiểm tra lại trạng thái; không tự gửi lại. ${failure.message}`); }
      if (actual.status !== 'IN_PROGRESS') return 'finished';
      throw new Error(`Máy chủ hiện vẫn báo đang làm bài. Hãy kiểm tra và chủ động thử lại. ${failure.message}`);
    }
  } finally { session.resumeEditing(); }
}
