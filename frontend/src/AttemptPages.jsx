import React, { useCallback, useEffect, useRef, useState } from 'react';
import { api, ApiError } from './api.js';
import { dateText, timezone } from './forms.js';
import { formatRemaining, remainingMilliseconds } from './attemptTime.js';
import { AnswerSession, answerStatus, answerCounts, answerLabels } from './answerSession.js';
import { submitAttempt } from './submission.js';

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
      <button className="primary" disabled={starting} onClick={() => navigate(`/student/attempts/${current.data.attemptId}${current.data.status !== 'IN_PROGRESS' ? '/result' : ''}`)}>{current.data.status !== 'IN_PROGRESS' ? 'Xem kết quả' : 'Tiếp tục'}</button></>}
    {!current.busy && current.absent && <><p>Chưa có lượt thi cho kỳ thi này.</p>
      {!exam.consentAccepted && <p>Bạn cần đọc và đồng ý nội dung giám sát trước khi bắt đầu.</p>}
      {exam.availability !== 'OPEN' && <p>{exam.availability === 'UPCOMING' ? 'Kỳ thi chưa mở.' : 'Kỳ thi đã đóng.'}</p>}
      <button className="primary" disabled={!canStart || starting} onClick={() => void start()}>{starting ? 'Đang bắt đầu…' : 'Bắt đầu thi'}</button></>}
    <p className="muted"><button disabled={starting || current.busy} onClick={refreshExam}>Cập nhật điều kiện kỳ thi</button></p>
    <p className="muted">Lựa chọn được tự động lưu. Nộp bài hoặc hết giờ sẽ kết thúc và chấm điểm; sau đó không thể sửa.</p>
  </section>;
}

export function AttemptPage({ id, navigate, onDirty }) {
  const [view, setView] = useState({ data: null, receivedAt: 0, busy: true, error: null });
  const viewRef = useRef(view);
  const session = useRef(null);
  const [answers, setAnswers] = useState({});
  const [timeoutError, setTimeoutError] = useState(null);
  const [submitBusy, setSubmitBusy] = useState(false);
  const [submitError, setSubmitError] = useState(null);
  const submitFlight = useRef(false);
  const preparation = useRef(0);
  const [viewing, setViewing] = useState(null);
  const [navOpen, setNavOpen] = useState(() => window.matchMedia('(min-width: 900px)').matches);
  const [monotonicNow, setMonotonicNow] = useState(() => performance.now());
  const expiryChecked = useRef(false);
  const timeoutFlight = useRef(false);
  const { data, receivedAt, busy, error } = view;
  const remaining = remainingMilliseconds(data, receivedAt, monotonicNow);
  const accept = useCallback((incoming) => {
    const old = viewRef.current;
    if (old.data && (Date.parse(incoming.serverTime) < Date.parse(old.data.serverTime)
      || (old.data.status !== 'IN_PROGRESS' && incoming.status !== old.data.status))) return;
    const next = { data: incoming, receivedAt: performance.now(), busy: false, error: null };
    viewRef.current = next; setView(next); setTimeoutError(null);
  }, []);
  const reload = useCallback(async () => {
    if (!session.current) return;
    setView(old => ({ ...old, busy: true }));
    try { await session.current.synchronize(); }
    catch (failure) {
      const next = { ...viewRef.current, busy: false, error: failure };
      viewRef.current = next; setView(next);
    }
  }, []);
  useEffect(() => {
    const store = new AnswerSession({
      save: (questionId, body) => api(`/student/attempts/${id}/answers/${questionId}`, 'PUT', body),
      read: () => api(`/student/attempts/${id}`),
      canWrite: () => {
        const latest = viewRef.current;
        return !latest.error && latest.data?.status === 'IN_PROGRESS'
          && remainingMilliseconds(latest.data, latest.receivedAt, performance.now()) > 0;
      },
      onAttempt: accept, changed: setAnswers,
    });
    session.current = store; void reload();
    return () => { store.dispose(); session.current = null; };
  }, [id, accept, reload]);
  const counts = answerCounts(answers);
  useEffect(() => { onDirty(counts.unsaved > 0); return () => onDirty(false); }, [counts.unsaved, onDirty]);
  useEffect(() => {
    if (data && data.status !== 'IN_PROGRESS') { onDirty(false); navigate(`/student/attempts/${id}/result`); }
  }, [data, id, navigate, onDirty]);
  useEffect(() => {
    const tick = window.setInterval(() => setMonotonicNow(performance.now()), 1000);
    const refresh = () => { if (document.visibilityState === 'visible') void reload(); };
    const poll = window.setInterval(refresh, 30000);
    window.addEventListener('focus', refresh); window.addEventListener('online', refresh);
    document.addEventListener('visibilitychange', refresh);
    return () => {
      window.clearInterval(tick); window.clearInterval(poll);
      window.removeEventListener('focus', refresh); window.removeEventListener('online', refresh);
      document.removeEventListener('visibilitychange', refresh);
    };
  }, [reload]);
  const confirmTimeout = useCallback(async () => {
    if (timeoutFlight.current) return;
    timeoutFlight.current = true;
    try {
      const result = await api(`/student/attempts/${id}/timeout`, 'POST');
      // Server confirms finalization; clear paper immediately even if the following GET fails.
      if (session.current) {
        accept({ ...viewRef.current.data, ...result, availability: 'EXPIRED', questions: [] });
        await session.current.synchronize();
      }
    } catch (failure) {
      if (!session.current) return;
      if (failure.problem?.code === 'TIMEOUT_NOT_DUE') {
        await reload(); // Fresh serverTime reanchors the countdown; no immediate POST loop.
      } else {
        setTimeoutError(failure);
        await reload();
      }
    } finally { timeoutFlight.current = false; }
  }, [id, accept, reload]);
  async function submit() {
    if (submitFlight.current || timeoutFlight.current || !session.current) return;
    const store = session.current; const generation = ++preparation.current;
    submitFlight.current = true; setSubmitBusy(true); setSubmitError(null);
    try {
      const outcome = await submitAttempt({ session: store,
        isCurrent: () => preparation.current === generation,
        beforeDeadline: () => remainingMilliseconds(viewRef.current.data, viewRef.current.receivedAt, performance.now()) > 0,
        confirm: ({ data: checked, answered, unanswered }) => window.confirm(`Nộp bài? Đã lưu ${answered}/${checked.questions.length} câu; bỏ trống ${unanswered} câu.\nSau khi nộp, bạn không thể sửa đáp án.`),
        send: () => api(`/student/attempts/${id}/submit`, 'POST'), expire: confirmTimeout,
      });
      if (store.active && outcome === 'finished') { onDirty(false); navigate(`/student/attempts/${id}/result`); }
    } catch (failure) { if (store.active) setSubmitError(failure); }
    finally {
      if (store.active) setSubmitBusy(false);
      submitFlight.current = false;
    }
  }
  useEffect(() => {
    if (remaining > 0) expiryChecked.current = false;
    if (data?.availability === 'ACTIVE' && remaining === 0 && !busy && !error && !expiryChecked.current) {
      expiryChecked.current = true;
      void confirmTimeout();
    }
  }, [remaining, data, busy, error, confirmTimeout]);
  const active = data?.status === 'IN_PROGRESS' && data?.availability === 'ACTIVE' && remaining > 0 && !busy && !error;
  useEffect(() => {
    if (!active || !data?.questions.length) return;
    const observer = new IntersectionObserver(entries => {
      const visible = entries.filter(entry => entry.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top);
      if (visible[0]) setViewing(Number(visible[0].target.dataset.question));
    }, { rootMargin: '-10% 0px -55% 0px' });
    document.querySelectorAll('[data-question]').forEach(element => observer.observe(element));
    return () => observer.disconnect();
  }, [data?.questions, active]);
  function jump(questionId) {
    setViewing(questionId);
    const heading = document.getElementById(`question-${questionId}`);
    heading?.scrollIntoView({ block: 'start' }); heading?.focus({ preventScroll: true });
  }
  return <><button className="back" onClick={() => navigate(data ? `/student/exams/${data.examId}` : '/student/exams')}>← Chi tiết kỳ thi</button>
    <AttemptError error={error} reload={() => void reload()} busy={busy} />
    {busy && <p role="status">Đang xác minh lượt thi từ máy chủ…</p>}
    {data && <><p className="eyebrow">LƯỢT THI #{data.attemptId}</p><h1>{data.title}</h1>
      <section className="card"><div className="section-heading"><h2>{data.availability === 'EXPIRED' ? 'Đã hết giờ' : 'Thời gian còn lại'}</h2>
        <button disabled={busy} onClick={() => void reload()}>Cập nhật trạng thái</button></div>
        <p className="attempt-clock" role="timer" aria-label="Thời gian còn lại ước tính từ máy chủ">{formatRemaining(remaining)}</p>
        <dl className="facts"><div><dt>Bắt đầu</dt><dd>{dateText(data.startedAt)}</dd></div><div><dt>Hạn truy cập</dt><dd>{dateText(data.deadlineAt)}</dd></div><div><dt>Máy chủ cập nhật lúc</dt><dd>{dateText(data.serverTime)}</dd></div></dl>
        <p className="muted">Múi giờ: {timezone}. Đồng hồ hiển thị được đồng bộ từ máy chủ.</p>
        {data.status !== 'IN_PROGRESS' ? <div className="notice" role="status">Máy chủ đã kết thúc và chấm bài. Đang mở kết quả…
          <p>Kết thúc hiệu lực: {dateText(data.effectiveEndedAt)} · Xử lý lúc: {dateText(data.finalizedAt)}</p></div>
          : remaining === 0 && <div className="notice error" role="alert">Đã hết giờ. Chưa xác nhận được trạng thái với server.
            <p>{timeoutError?.message || 'Đang kiểm tra trạng thái. Không thể chỉnh sửa đáp án.'}</p>
            <button disabled={busy || timeoutFlight.current} onClick={() => void confirmTimeout()}>Xác nhận lại trạng thái hết giờ</button></div>}
      </section>
      {submitError && <div className="notice error" role="alert">{submitError.message}</div>}
      {active && <section className="card submission-bar"><button className="primary" disabled={submitBusy} onClick={() => void submit()}>{submitBusy ? 'Đang chờ lưu và kiểm tra bài…' : 'Nộp bài'}</button>
        <p>Chỉ các đáp án đã được máy chủ lưu mới được chấm. Sau khi nộp không thể sửa.</p>
      </section>}
      {counts.unsaved > 0 && <div className={Object.values(answers).some(a => a.issue) ? 'notice error' : 'notice'} role="status">Có {counts.unsaved} câu chưa được xác nhận lưu. {remaining === 0 ? 'Không thể gửi thêm đáp án sau hạn. Chỉ đáp án máy chủ đã nhận hợp lệ được giữ lại.' : 'Không đóng hoặc tải lại trang nếu muốn giữ các lựa chọn chưa lưu.'}</div>}
      {active && <div className="attempt-layout"><aside><details className="card question-nav" open={navOpen} onToggle={event => setNavOpen(event.currentTarget.open)}>
        <summary>Điều hướng câu hỏi
          <span className="answer-counts" role="status">Đã chọn: {counts.selected}/{counts.total} · Chưa trả lời: {counts.unanswered} · Chưa lưu: {counts.unsaved}</span>
        </summary>
        <nav aria-label="Chuyển đến câu hỏi" className="question-grid">{data.questions.map((q, index) => {
          const status = answers[q.id] ? answerStatus(answers[q.id]) : 'unanswered';
          const current = viewing === q.id || (viewing === null && index === 0);
          return <button key={q.id} className={`question-number ${status}`} aria-current={current ? 'step' : undefined}
            aria-label={`Câu ${index + 1}: ${answerLabels[status]}${current ? ', đang xem' : ''}`} title={answerLabels[status]} onClick={() => jump(q.id)}>
            <strong>{index + 1}</strong><span aria-hidden="true">{answerLabels[status].split(' ')[0]}</span>{current && <small>Đang xem</small>}</button>;
        })}</nav>
        <ul className="nav-legend">{Object.entries(answerLabels).map(([status, label]) => <li key={status}>{label}</li>)}</ul>
        <p className="muted">Đã chọn chưa có nghĩa đã lưu. Bỏ chọn cũng cần máy chủ xác nhận.</p>
      </details></aside><section aria-label="Đề thi">{data.questions.length === 0 ? <p className="card empty">Không có câu hỏi trong phản hồi.</p> : data.questions.map((question, index) => {
        const answer = answers[question.id];
        if (!answer) return null;
        const status = answerStatus(answer);
        return <article className="card answer-question" data-question={question.id} key={question.id}>
          <h2 tabIndex={-1} id={`question-${question.id}`}>Câu {index + 1}</h2><p className="prewrap">{question.content}</p>
          <fieldset disabled={!active || submitBusy || Boolean(answer.issue)}><legend className="sr-only">Chọn một đáp án cho câu {index + 1}</legend>
            {question.options.map((option, n) => <label className="answer-option" key={option.id}><input type="radio" name={`answer-${question.id}`} checked={answer.desired === option.id}
              onChange={() => session.current.choose(question.id, option.id)} /><span className="prewrap">{String.fromCharCode(65 + n)}. {option.content}</span></label>)}
            <button type="button" disabled={answer.desired === null} onClick={() => session.current.choose(question.id, null)}>Bỏ chọn câu {index + 1}</button>
          </fieldset><p className={`answer-state ${status}`} role="status">{answerLabels[status]}{status === 'unanswered' && answer.revision > 0 ? ' · Máy chủ đã xác nhận bỏ chọn' : ''}</p>
          {answer.issue && <div className="notice error" role="alert"><p>{answer.message}</p><button disabled={answer.saving || submitBusy} onClick={() => void session.current.retry(question.id)}>
            {answer.issue === 'conflict' ? 'Dùng dữ liệu máy chủ để chọn lại' : 'Đồng bộ và thử lưu lựa chọn hiện tại'}</button></div>}
        </article>;
      })}</section></div>}
    </>}
  </>;
}
