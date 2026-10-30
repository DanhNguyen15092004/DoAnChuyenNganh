import React, { useEffect, useState } from 'react';
import { api } from './api.js';
import { dateText, timezone } from './forms.js';

const statusText = { IN_PROGRESS: 'Đang làm', SUBMITTED: 'Đã nộp', TIMED_OUT: 'Hết giờ' };
const scoreText = value => value === null || value === undefined ? 'Chưa có điểm' : `${new Intl.NumberFormat('vi-VN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(value)} / 10`;
function useResultLoad(path) {
  const [revision, setRevision] = useState(0);
  const [state, setState] = useState({ data: null, busy: true, error: null });
  useEffect(() => {
    let active = true;
    api(path).then(data => { if (active) setState({ data, busy: false, error: null }); })
      .catch(error => { if (active) setState({ data: null, busy: false, error }); });
    return () => { active = false; };
  }, [path, revision]);
  return { ...state, reload: () => { setState(old => ({ ...old, busy: true, error: null })); setRevision(n => n + 1); } };
}
function LoadState({ load }) {
  return <>{load.busy && <p role="status">Đang tải dữ liệu từ máy chủ…</p>}{load.error && <div className="notice error" role="alert">
    <p>{load.error.problem?.code === 'RESULT_NOT_READY' ? 'Lượt thi chưa kết thúc, chưa có kết quả.' : load.error.message}</p>
    <button disabled={load.busy} onClick={load.reload}>Kiểm tra lại</button></div>}</>;
}
function ResultDetails({ result }) {
  return <section className="card"><p className="eyebrow">LƯỢT THI #{result.attemptId} · {statusText[result.status]}</p>
    <h1>{result.title}</h1><p className="result-score" aria-label="Điểm bài thi">{scoreText(result.score)}</p>
    <dl className="facts"><div><dt>Tổng câu hỏi</dt><dd>{result.totalQuestions}</dd></div><div><dt>Trả lời đúng</dt><dd>{result.correctCount}</dd></div>
      <div><dt>Đã trả lời</dt><dd>{result.answeredCount}</dd></div><div><dt>Bỏ trống</dt><dd>{result.unansweredCount}</dd></div>
      <div><dt>Bắt đầu</dt><dd>{dateText(result.startedAt)}</dd></div><div><dt>Hạn làm bài</dt><dd>{dateText(result.deadlineAt)}</dd></div>
      <div><dt>Kết thúc hiệu lực</dt><dd>{dateText(result.effectiveEndedAt)}</dd></div><div><dt>Chấm lúc</dt><dd>{dateText(result.gradedAt)}</dd></div></dl>
    <p>{result.status === 'SUBMITTED' ? 'Sinh viên đã chủ động nộp bài trước hạn.' : 'Lượt thi đã kết thúc do hết giờ.'} Không thể chỉnh sửa bài đã kết thúc.</p>
    <p className="muted">Múi giờ: {timezone}. Kết quả không công bố đáp án đúng hoặc lời giải.</p>
  </section>;
}
export function StudentResultPage({ id, navigate }) {
  const load = useResultLoad(`/student/attempts/${id}/result`);
  return <><button className="back" onClick={() => navigate('/student/exams')}>← Kỳ thi của tôi</button><LoadState load={load} />
    {load.data && !load.busy && <ResultDetails result={load.data} />}</>;
}
export function TeacherAttemptList({ examId, navigate }) {
  const [page, setPage] = useState(0);
  const path = `/teacher/exams/${examId}/attempts`;
  const load = useResultLoad(`${path}?page=${page}&size=20`);
  return <><button className="back" onClick={() => navigate(`/teacher/exams/${examId}`)}>← Chi tiết kỳ thi</button>
    <div className="section-heading"><h1>Bài thi · Kỳ thi #{examId}</h1><button disabled={load.busy} onClick={load.reload}>Cập nhật danh sách</button></div>
    <p>Danh sách chỉ gồm sinh viên đã bắt đầu lượt thi. Ghi danh chưa bắt đầu vẫn nằm trong danh sách ghi danh.</p>
    <LoadState load={load} />{load.data && !load.busy && <><section className="card">
      {load.data.content.length === 0 ? <p className="empty">Chưa có lượt thi trên trang này.</p> : <ul className="attempt-list">{load.data.content.map(a => <li key={a.attemptId}>
        <div><h2>{a.student.displayName}</h2><p>{a.student.email} · ID {a.student.id}</p><p>{statusText[a.status]} · {scoreText(a.score)}</p>
          <p>Bắt đầu: {dateText(a.startedAt)}<br />Kết thúc: {a.effectiveEndedAt ? dateText(a.effectiveEndedAt) : 'Chưa kết thúc'}<br />Hạn: {dateText(a.deadlineAt)}</p>
          {!a.resultReady && a.status !== 'IN_PROGRESS' && <p role="status">Kết quả đang chờ xử lý, chưa có điểm.</p>}</div>
        <button onClick={() => navigate(`${path}/${a.attemptId}/result`)}>Xem kết quả · #{a.attemptId}</button></li>)}</ul>}
    </section><nav className="pager" aria-label="Phân trang bài thi"><button disabled={load.busy || page === 0} onClick={() => setPage(p => p - 1)}>← Trước</button>
      <span>Trang {load.data.totalPages ? load.data.page + 1 : 0}/{load.data.totalPages} · {load.data.totalElements} lượt</span>
      <button disabled={load.busy || page + 1 >= load.data.totalPages} onClick={() => setPage(p => p + 1)}>Sau →</button></nav></>}
  </>;
}
export function TeacherResultPage({ examId, id, navigate }) {
  const load = useResultLoad(`/teacher/exams/${examId}/attempts/${id}/result`);
  return <><button className="back" onClick={() => navigate(`/teacher/exams/${examId}/attempts`)}>← Danh sách bài thi</button><LoadState load={load} />
    {load.data && !load.busy && <><section className="notice"><strong>{load.data.student.displayName}</strong><p>{load.data.student.email} · ID {load.data.student.id}</p></section>
      <ResultDetails result={load.data.result} /></>}</>;
}
