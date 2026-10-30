import React, { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError } from './api.js';
import { dateText, timezone } from './forms.js';
import { formatRemaining, remainingMilliseconds } from './attemptTime.js';

function useAttempt(path, allowMissing = false) {
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState({ data: null, receivedAt: 0, busy: true, error: null, absent: false });
  const reload = useCallback(() => {
    setState(old => ({ ...old, busy: true }));
    setRevision(value => value + 1);
  }, []);
  useEffect(() => {
    let active = true;
    api(path).then(data => {
      if (active) setState({ data, receivedAt: performance.now(), busy: false, error: null, absent: false });
    }).catch(error => {
      if (!active) return;
      const absent = allowMissing && error instanceof ApiError && error.status === 404;
      setState(old => ({ ...old, data: null, busy: false, error: absent ? null : error, absent }));
    });
    // Ignore older requests after a route change, focus event or manual refresh.
    return () => { active = false; };
  }, [path, allowMissing, revision]);
  return { ...state, reload };
}
function AttemptError({ error, reload, busy }) {
  return error ? <div className="notice error" role="alert"><p>{error.message || 'Không thể tải lượt thi.'}</p>
    <button disabled={busy} onClick={reload}>Kiểm tra lại từ máy chủ</button></div> : null;
}

export function AttemptAction({ exam, navigate, refreshExam }) {
  const current = useAttempt(`/student/exams/${exam.id}/attempt`, true);
  const [starting, setStarting] = useState(false);
  const [error, setError] = useState(null);
  const canStart = current.absent && exam.availability === 'OPEN' && exam.consentAccepted;
  async function start() {
    if (starting || !canStart || current.busy) return;
    if (!window.confirm('Bắt đầu lượt thi? Thời gian vẫn tiếp tục chạy khi đóng trang hoặc mất kết nối.')) return;
    setStarting(true); setError(null);
    try {
      // No body, no client identity/timestamps, and never automatically retry this POST.
      const attempt = await api(`/student/exams/${exam.id}/attempt`, 'POST');
      navigate(`/student/attempts/${attempt.attemptId}`);
    } catch (failure) { setError(failure); }
    finally { setStarting(false); }
  }
  function checkAgain() { setError(null); current.reload(); }
  return <section className="card"><h2>Lượt thi của bạn</h2>
    {current.busy && <p role="status">Đang kiểm tra lượt thi hiện có…</p>}
    <AttemptError error={current.error || error} reload={checkAgain} busy={starting || current.busy} />
    {!current.busy && current.data && <><p>{current.data.availability === 'ACTIVE' ? 'Bạn đã bắt đầu lượt thi. Deadline không thay đổi khi tải lại trang.' : 'Lượt thi đã hết thời gian truy cập.'}</p>
      <p>Hạn truy cập: {dateText(current.data.deadlineAt)} · {timezone}</p>
      <button className="primary" disabled={starting} onClick={() => navigate(`/student/attempts/${current.data.attemptId}`)}>{current.data.availability === 'ACTIVE' ? 'Tiếp tục' : 'Xem trạng thái'}</button></>}
    {!current.busy && current.absent && <><p>Chưa có lượt thi cho kỳ thi này.</p>
      {!exam.consentAccepted && <p>Bạn cần đọc và đồng ý nội dung giám sát trước khi bắt đầu.</p>}
      {exam.availability !== 'OPEN' && <p>{exam.availability === 'UPCOMING' ? 'Kỳ thi chưa mở.' : 'Kỳ thi đã đóng.'}</p>}
      <button className="primary" disabled={!canStart || starting} onClick={() => void start()}>{starting ? 'Đang bắt đầu…' : 'Bắt đầu thi'}</button></>}
    <p className="muted"><button disabled={starting || current.busy} onClick={refreshExam}>Cập nhật điều kiện kỳ thi</button></p>
    <p className="muted">Chức năng lưu đáp án đang được phát triển.</p>
  </section>;
}

export function AttemptPage({ id, navigate }) {
  const { data, receivedAt, busy, error, reload } = useAttempt(`/student/attempts/${id}`);
  const [monotonicNow, setMonotonicNow] = useState(() => performance.now());
  const expiryChecked = useRef(false);
  const remaining = remainingMilliseconds(data, receivedAt, monotonicNow);
  useEffect(() => {
    const tick = window.setInterval(() => setMonotonicNow(performance.now()), 1000);
    // Reads only: resynchronize after tab sleep/focus and periodically while visible.
    const refresh = () => { if (document.visibilityState === 'visible') reload(); };
    const poll = window.setInterval(refresh, 30000);
    window.addEventListener('focus', refresh);
    document.addEventListener('visibilitychange', refresh);
    return () => {
      window.clearInterval(tick); window.clearInterval(poll);
      window.removeEventListener('focus', refresh); document.removeEventListener('visibilitychange', refresh);
    };
  }, [reload]);
  useEffect(() => {
    if (remaining > 0) expiryChecked.current = false;
    if (data?.availability === 'ACTIVE' && remaining === 0 && !busy && !error && !expiryChecked.current) {
      expiryChecked.current = true;
      reload();
    }
  }, [remaining, data, busy, error, reload]);
  const active = data?.availability === 'ACTIVE' && remaining > 0 && !busy && !error;
  return <><button className="back" onClick={() => navigate(data ? `/student/exams/${data.examId}` : '/student/exams')}>← Chi tiết kỳ thi</button>
    <AttemptError error={error} reload={reload} busy={busy} />
    {busy && <p role="status">Đang xác minh lượt thi từ máy chủ…</p>}
    {data && <><p className="eyebrow">LƯỢT THI #{data.attemptId}</p><h1>{data.title}</h1>
      <section className="card"><div className="section-heading"><h2>{data.availability === 'EXPIRED' ? 'Đã hết giờ' : 'Thời gian còn lại'}</h2>
        <button disabled={busy} onClick={reload}>Cập nhật trạng thái</button></div>
        <p className="attempt-clock" role="timer" aria-label="Thời gian còn lại ước tính từ máy chủ">{formatRemaining(remaining)}</p>
        <dl className="facts"><div><dt>Bắt đầu</dt><dd>{dateText(data.startedAt)}</dd></div><div><dt>Hạn truy cập</dt><dd>{dateText(data.deadlineAt)}</dd></div><div><dt>Máy chủ cập nhật lúc</dt><dd>{dateText(data.serverTime)}</dd></div></dl>
        <p className="muted">Múi giờ: {timezone}. Đồng hồ hiển thị được đồng bộ từ máy chủ.</p>
        {data.availability === 'EXPIRED' && <div className="notice" role="status">Đã hết thời gian truy cập đề. Trạng thái này chưa có nghĩa bài đã được nộp hoặc chấm điểm.</div>}
        {data.availability === 'ACTIVE' && remaining === 0 && <p role="status">Đang chờ máy chủ xác nhận trạng thái hết giờ. Đề thi tạm ẩn.</p>}
      </section>
      <div className="notice">Chức năng lưu đáp án đang được phát triển. Các lựa chọn dưới đây chỉ để đọc.</div>
      {active && <section aria-label="Đề thi">{data.questions.length === 0 ? <p className="card empty">Không có câu hỏi trong phản hồi.</p> : data.questions.map((question, index) =>
        <article className="card" key={question.id}><h2>Câu {index + 1}</h2><p className="prewrap">{question.content}</p>
          <ol className="attempt-options" type="A">{question.options.map(option => <li className="prewrap" key={option.id}>{option.content}</li>)}</ol></article>)}</section>}
    </>}
  </>;
}
