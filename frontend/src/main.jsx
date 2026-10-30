import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { api, ApiError, refreshCsrf } from './api.js';
import { dateText, metadataError, questionPayload, questionsError, timezone, toInstant, toLocalInput } from './forms.js';
import { AttemptAction, AttemptPage } from './AttemptPages.jsx';
import './style.css';

const teacherPath = '/teacher/exams';
const studentPath = '/student/exams';
const roleLabel = { STUDENT: 'Sinh viên', TEACHER: 'Giáo viên', ADMIN: 'Quản trị viên' };
const availabilityLabel = { UPCOMING: 'Chưa mở', OPEN: 'Đang mở', CLOSED: 'Đã đóng' };
function message(error) { return error instanceof Error ? error.message : 'Có lỗi xảy ra. Hãy thử lại.'; }

function Notice({ error, success, reload }) {
  if (error) return <div className="notice error" role="alert"><strong>Chưa thực hiện được</strong><p>{message(error)}</p>
    {error instanceof ApiError && error.problem.errors?.length ? <ul>{error.problem.errors.map((e, i) => <li key={i}>{e.field}: {e.message}</li>)}</ul> : null}
    {reload && <button type="button" onClick={reload}>Tải lại dữ liệu</button>}</div>;
  return success ? <div className="notice success" role="status">{success}</div> : null;
}
function Pager({ data, onPage, busy }) {
  return <nav className="pager" aria-label="Phân trang"><button disabled={busy || data.page === 0} onClick={() => onPage(data.page - 1)}>← Trước</button>
    <span>Trang {data.totalPages ? data.page + 1 : 0}/{data.totalPages} · {data.totalElements} mục</span>
    <button disabled={busy || data.page + 1 >= data.totalPages} onClick={() => onPage(data.page + 1)}>Sau →</button></nav>;
}
function useLoad(path) {
  const [data, setData] = useState(null);
  const [error, setError] = useState();
  const [busy, setBusy] = useState(true);
  const [revision, setRevision] = useState(0);
  const reload = useCallback(() => setRevision(r => r + 1), []);
  useEffect(() => {
    let active = true;
    setBusy(true); setError(undefined); setData(null);
    api(path).then(value => { if (active) setData(value); })
      .catch(e => { if (active) setError(e); }).finally(() => { if (active) setBusy(false); });
    return () => { active = false; };
  }, [path, revision]);
  return { data, error, busy, reload };
}

function Login({ onLogin, navigate }) {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState();
  async function submit(e) {
    e.preventDefault(); if (busy) return;
    setBusy(true); setError(undefined);
    try {
      await refreshCsrf();
      await api('/auth/login', 'POST', { email, password }, true);
      setPassword('');
      await refreshCsrf();
      onLogin(await api('/auth/me'));
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
  return <div className="login-layout"><section className="intro"><p className="eyebrow">KHÔNG GIAN KỲ THI</p><h1>Chuẩn bị đề.<br />Sẵn sàng tham gia.</h1>
    <p>Giáo viên quản lý kỳ thi và ghi danh. Sinh viên xem lịch thi, xác nhận giám sát và mở đề.</p><p className="muted">Chức năng lưu đáp án đang được phát triển.</p></section>
    <section className="card login"><h2>Đăng nhập</h2><p>Dùng tài khoản được cấp cho bạn.</p><form onSubmit={submit}>
      <fieldset disabled={busy}><label>Email<input type="email" autoComplete="username" maxLength={254} required value={email} onChange={e => setEmail(e.target.value)} /></label>
        <label>Mật khẩu<input type="password" autoComplete="current-password" required value={password} onChange={e => setPassword(e.target.value)} /></label>
        <button className="primary wide" type="submit">{busy ? 'Đang đăng nhập…' : 'Đăng nhập'}</button></fieldset>
      <Notice error={error} /></form><p className="muted">Chưa có tài khoản? <button type="button" onClick={() => navigate('/register')}>Đăng ký sinh viên</button></p></section></div>;
}

function Register({ navigate, onRegistered }) {
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [confirmation, setConfirmation] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState();
  async function submit(event) {
    event.preventDefault(); if (busy) return;
    setError(undefined);
    if (password !== confirmation) { setError(new Error('Mật khẩu xác nhận không khớp.')); return; }
    if (new TextEncoder().encode(password).length > 72) { setError(new Error('Mật khẩu không được vượt quá 72 byte UTF-8.')); return; }
    setBusy(true);
    try {
      await refreshCsrf();
      const account = await api('/auth/register', 'POST', { email, password, displayName }, true);
      setPassword(''); setConfirmation(''); onRegistered(account);
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
  return <section className="card login" style={{ maxWidth: 540, margin: '0 auto' }}><p className="eyebrow">TÀI KHOẢN SINH VIÊN</p><h1>Đăng ký</h1>
    <p>Tạo tài khoản để được giáo viên ghi danh vào kỳ thi.</p><form onSubmit={submit}><fieldset disabled={busy}>
      <label>Họ và tên<input required autoComplete="name" maxLength={100} value={displayName} onChange={e => setDisplayName(e.target.value)} /></label>
      <label>Email<input required type="email" autoComplete="username" maxLength={254} value={email} onChange={e => setEmail(e.target.value)} /></label>
      <label>Mật khẩu<input required type="password" autoComplete="new-password" minLength={8} maxLength={72} value={password} onChange={e => setPassword(e.target.value)} /></label>
      <p className="muted">Ít nhất 8 ký tự, tối đa 72 byte UTF-8. Mật khẩu được giữ nguyên khoảng trắng.</p>
      <label>Nhập lại mật khẩu<input required type="password" autoComplete="new-password" minLength={8} maxLength={72} value={confirmation} onChange={e => setConfirmation(e.target.value)} /></label>
      <button type="submit" className="primary wide">{busy ? 'Đang đăng ký…' : 'Tạo tài khoản sinh viên'}</button>
    </fieldset><Notice error={error} /></form><button className="back" style={{ marginTop: '1rem' }} disabled={busy} onClick={() => navigate('/login')}>← Về đăng nhập</button></section>;
}

function ExamList({ student, navigate }) {
  const [page, setPage] = useState(0);
  const prefix = student ? studentPath : teacherPath;
  const { data, error, busy, reload } = useLoad(`${prefix}?page=${page}&size=20`);
  return <><div className="section-heading"><div><p className="eyebrow">{student ? 'LỊCH CỦA BẠN' : 'QUẢN LÝ'}</p><h1>{student ? 'Kỳ thi đã ghi danh' : 'Kỳ thi'}</h1>
    <p>{student ? 'Xem thông tin kỳ thi và xác nhận đồng ý giám sát.' : 'Soạn đề nháp, xuất bản và ghi danh sinh viên.'}</p></div>
    {!student && <button className="primary" onClick={() => navigate('/teacher/exams/new')}>+ Tạo kỳ thi nháp</button>}</div>
    <Notice error={error} reload={reload} />{busy && <p role="status">Đang tải kỳ thi…</p>}
    {data && <><div className="list">{data.content.length === 0 ? <div className="card empty">{student ? 'Bạn chưa được ghi danh vào kỳ thi nào.' : 'Chưa có kỳ thi trong phạm vi quản lý của bạn.'}</div> : data.content.map(exam =>
      <button className="exam-card" key={exam.id} onClick={() => navigate(`${prefix}/${exam.id}`)}><div className="card-top"><span className="badge">{'status' in exam ? exam.status === 'DRAFT' ? 'Bản nháp' : 'Đã xuất bản' : availabilityLabel[exam.availability]}</span><span>#{exam.id}</span></div>
        <h2>{exam.title}</h2><p>{exam.description || 'Chưa có mô tả.'}</p><div className="card-bottom"><span>{exam.durationMinutes} phút</span><span>{dateText(exam.opensAt)}</span></div>
        {'consentAccepted' in exam && <p>{exam.consentAccepted ? '✓ Đã đồng ý giám sát' : 'Chưa xác nhận giám sát'}</p>}<span className="view-link">Xem chi tiết →</span></button>)}</div>
      <Pager data={data} onPage={setPage} busy={busy} /></>}
    <p className="muted">Thời gian hiển thị theo {timezone}.</p></>;
}

function metadataForm(data) {
  return data ? { title: data.title, description: data.description || '', durationMinutes: String(data.durationMinutes), opensAt: toLocalInput(data.opensAt), closesAt: toLocalInput(data.closesAt) }
    : { title: '', description: '', durationMinutes: '30', opensAt: '', closesAt: '' };
}
function MetadataFields({ value, onChange }) {
  function field(name, next) { onChange({ ...value, [name]: next }); }
  return <><label>Tiêu đề<input required maxLength={200} value={value.title} onChange={e => field('title', e.target.value)} /></label>
    <label>Mô tả<textarea maxLength={5000} rows={3} value={value.description} onChange={e => field('description', e.target.value)} /></label>
    <div className="form-grid"><label>Thời lượng (phút)<input type="number" required min={1} max={300} step={1} value={value.durationMinutes} onChange={e => field('durationMinutes', e.target.value)} /></label>
      <label>Mở thi<input type="datetime-local" step="0.001" required value={value.opensAt} onChange={e => field('opensAt', e.target.value)} /></label>
      <label>Đóng thi<input type="datetime-local" step="0.001" required value={value.closesAt} onChange={e => field('closesAt', e.target.value)} /></label></div>
    <p className="muted">Nhập giờ theo {timezone}. Khoảng mở thi phải dài ít nhất bằng thời lượng.</p></>;
}
function toMetadata(form, original) {
  // Preserve server microseconds when the user did not change that timestamp.
  const instant = key => original && toLocalInput(original[key]) === form[key] ? original[key] : toInstant(form[key]);
  const data = { title: form.title, description: form.description, durationMinutes: Number(form.durationMinutes), opensAt: instant('opensAt'), closesAt: instant('closesAt') };
  const error = metadataError(data); if (error) throw new Error(error); return data;
}

function CreateExam({ navigate, onDirty }) {
  const [form, setForm] = useState(() => metadataForm());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState();
  useEffect(() => { onDirty(JSON.stringify(form) !== JSON.stringify(metadataForm())); return () => onDirty(false); }, [form, onDirty]);
  async function submit(e) {
    e.preventDefault(); if (busy) return; setBusy(true); setError(undefined);
    try { const exam = await api(teacherPath, 'POST', toMetadata(form)); onDirty(false); navigate(`${teacherPath}/${exam.id}`); }
    catch (e) { setError(e); } finally { setBusy(false); }
  }
  return <><button className="back" onClick={() => navigate(teacherPath)}>← Danh sách kỳ thi</button><h1>Tạo kỳ thi nháp</h1><section className="card"><form onSubmit={submit}><fieldset disabled={busy}><MetadataFields value={form} onChange={setForm} />
    <button className="primary" type="submit">{busy ? 'Đang tạo…' : 'Tạo bản nháp'}</button></fieldset><Notice error={error} /></form></section></>;
}

function QuestionEditor({ value, onChange, readOnly, busy }) {
  function update(index, patch) { onChange(value.map((q, i) => i === index ? { ...q, ...patch } : q)); }
  function move(array, from, to) { const next = [...array]; [next[from], next[to]] = [next[to], next[from]]; return next; }
  return <fieldset disabled={readOnly || busy}><legend className="sr-only">Nội dung câu hỏi</legend>
    {!value.length && <p className="empty">Chưa có câu hỏi. Thêm ít nhất một câu để xuất bản.</p>}
    {value.map((q, i) => <section className="question" key={i}><div className="section-heading"><h3>Câu {i + 1}</h3>{!readOnly && <div className="actions"><button type="button" aria-label={`Đưa câu ${i + 1} lên`} disabled={i === 0 || busy} onClick={() => onChange(move(value, i, i - 1))}>↑</button><button type="button" aria-label={`Đưa câu ${i + 1} xuống`} disabled={i === value.length - 1 || busy} onClick={() => onChange(move(value, i, i + 1))}>↓</button><button type="button" onClick={() => onChange(value.filter((_, n) => n !== i))}>Xóa câu {i + 1}</button></div>}</div>
      <label>Nội dung câu {i + 1}<textarea required maxLength={5000} rows={3} value={q.content} onChange={e => update(i, { content: e.target.value })} /></label>
      <p className="muted">Chọn đúng một đáp án đúng.</p>{q.options.map((o, j) => <div className="option" key={j}>
        <label className="radio-label"><input type="radio" name={`correct-${i}`} checked={o.correct} onChange={() => update(i, { options: q.options.map((o, n) => ({ ...o, correct: n === j })) })} />Đúng {j + 1}</label>
        <label className="option-content"><span className="sr-only">Câu {i + 1}, lựa chọn {j + 1}</span><textarea required rows={2} maxLength={2000} value={o.content} onChange={e => update(i, { options: q.options.map((o, n) => n === j ? { ...o, content: e.target.value } : o) })} /></label>
        {!readOnly && <div className="actions"><button type="button" aria-label={`Đưa lựa chọn ${j + 1} câu ${i + 1} lên`} disabled={j === 0 || busy} onClick={() => update(i, { options: move(q.options, j, j - 1) })}>↑</button><button type="button" aria-label={`Đưa lựa chọn ${j + 1} câu ${i + 1} xuống`} disabled={j === q.options.length - 1 || busy} onClick={() => update(i, { options: move(q.options, j, j + 1) })}>↓</button><button type="button" disabled={q.options.length <= 2 || busy} aria-label={`Xóa lựa chọn ${j + 1} câu ${i + 1}`} onClick={() => update(i, { options: q.options.filter((_, n) => n !== j) })}>Xóa</button></div>}</div>)}
      {!readOnly && <button type="button" disabled={q.options.length >= 6 || busy} onClick={() => update(i, { options: [...q.options, { content: '', position: q.options.length + 1, correct: false }] })}>+ Thêm lựa chọn</button>}</section>)}
    {!readOnly && <button type="button" disabled={value.length >= 100 || busy} onClick={() => onChange([...value, { content: '', position: value.length + 1, options: [{ content: '', position: 1, correct: false }, { content: '', position: 2, correct: false }] }])}>+ Thêm câu hỏi ({value.length}/100)</button>}
  </fieldset>;
}

function Enrollments({ id, published }) {
  const [page, setPage] = useState(0);
  const [studentId, setStudentId] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState();
  const [success, setSuccess] = useState('');
  const list = useLoad(`${teacherPath}/${id}/enrollments?page=${page}&size=20`);
  async function submit(e) {
    e.preventDefault(); if (busy) return; setBusy(true); setError(undefined); setSuccess('');
    try {
      const target = Number(studentId);
      if (!Number.isSafeInteger(target) || target < 1) throw new Error('Student ID phải là số nguyên dương hợp lệ.');
      const result = await api(`${teacherPath}/${id}/enrollments`, 'POST', { studentId: target });
      setSuccess(`Đã ghi danh ${result.student.displayName} (ID ${result.student.id}).`); list.reload();
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
  return <section className="card"><h2>Ghi danh & đồng ý giám sát</h2><p>Sinh viên đăng nhập và lấy ID tài khoản của mình, sau đó cung cấp ID cho giáo viên.</p>
    {!published && <p>Xuất bản kỳ thi trước khi ghi danh.</p>}
    {published && <form onSubmit={submit}><fieldset className="inline-form" disabled={busy}><label>Student ID<input required inputMode="numeric" type="number" min={1} step={1} value={studentId} onChange={e => setStudentId(e.target.value)} /></label><button className="primary" type="submit">{busy ? 'Đang ghi danh…' : 'Ghi danh'}</button></fieldset></form>}
    <Notice error={error} success={success} reload={list.reload} /><Notice error={list.error} reload={list.reload} />
    <div className="section-heading"><h3>Danh sách sinh viên</h3><button disabled={list.busy} onClick={list.reload}>Làm mới danh sách</button></div>
    {list.busy && <p role="status">Đang tải ghi danh…</p>}{list.data && <>{list.data.content.length ? <ul className="enrollments">{list.data.content.map(n => <li key={n.id}><strong>{n.student.displayName}</strong><span>{n.student.email} · ID {n.student.id}</span><span>{n.consentAccepted ? `✓ Đã đồng ý · ${dateText(n.acceptedAt)}` : 'Chưa đồng ý giám sát'}</span></li>)}</ul> : <p className="empty">Chưa có sinh viên được ghi danh.</p>}<Pager data={list.data} onPage={setPage} busy={list.busy} /></>}
  </section>;
}

function TeacherDetail({ id, navigate, onDirty }) {
  const load = useLoad(`${teacherPath}/${id}`);
  return <><button className="back" onClick={() => navigate(teacherPath)}>← Danh sách kỳ thi</button><Notice error={load.error} reload={load.reload} />
    {load.busy && <p role="status">Đang tải chi tiết…</p>}{load.data && <TeacherEditor key={`${load.data.id}:${load.data.updatedAt}`} initial={load.data} onDirty={onDirty} reload={load.reload} />}</>;
}
function TeacherEditor({ initial, onDirty, reload }) {
  const [saved, setSaved] = useState(initial);
  const [form, setForm] = useState(() => metadataForm(initial));
  const [questions, setQuestions] = useState(() => questionPayload(initial.questions));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState();
  const [success, setSuccess] = useState('');
  const metaDirty = JSON.stringify(form) !== JSON.stringify(metadataForm(saved));
  const questionDirty = JSON.stringify(questionPayload(questions)) !== JSON.stringify(questionPayload(saved.questions));
  const dirty = metaDirty || questionDirty;
  const published = saved.status === 'PUBLISHED';
  useEffect(() => { onDirty(dirty); return () => onDirty(false); }, [dirty, onDirty]);
  function safeReload() { if (!dirty || window.confirm('Có thay đổi chưa lưu. Tải lại và bỏ các thay đổi này?')) reload(); }
  async function mutate(operation) {
    if (busy) return;
    if (operation === 'publish' && (dirty || !window.confirm('Sau khi publish, nội dung kỳ thi không thể chỉnh sửa.'))) return;
    setBusy(true); setError(undefined); setSuccess('');
    try {
      let result;
      const base = `${teacherPath}/${saved.id}`;
      if (operation === 'metadata') {
        result = await api(base, 'PUT', toMetadata(form, saved));
        setForm(metadataForm(result));
        // Keep the independent, possibly unsaved question editor intact.
        setSaved(old => ({ ...result, questions: old.questions }));
      } else if (operation === 'questions') {
        const problem = questionsError(questions); if (problem) throw new Error(problem);
        result = await api(`${base}/questions`, 'PUT', { questions: questionPayload(questions) });
        setQuestions(questionPayload(result.questions));
        // Preserve metadata form when saving the separate question resource.
        setSaved(old => ({ ...old, questions: result.questions, updatedAt: result.updatedAt, version: result.version }));
      } else {
        result = await api(`${base}/publish`, 'POST'); setSaved(result); setForm(metadataForm(result)); setQuestions(questionPayload(result.questions));
      }
      setSuccess(operation === 'publish' ? 'Đã xuất bản kỳ thi. Nội dung chuyển sang chỉ đọc.' : operation === 'metadata' ? 'Đã lưu thông tin kỳ thi.' : 'Đã lưu toàn bộ câu hỏi.');
    } catch (e) { setError(e); } finally { setBusy(false); }
  }
  return <><div className="section-heading"><div><p className="eyebrow">KỲ THI #{saved.id} · CHỦ SỞ HỮU #{saved.ownerId}</p><h1>{saved.title}</h1><span className="badge">{published ? 'Đã xuất bản · Chỉ đọc' : 'Bản nháp'}</span></div>
    <div className="actions"><button disabled={busy} onClick={safeReload}>Tải lại</button>{!published && <button className="primary" disabled={busy || dirty || saved.questions.length === 0} onClick={() => void mutate('publish')}>Xuất bản kỳ thi</button>}</div></div>
    <p className={dirty ? 'dirty' : 'muted'} role="status">{dirty ? '● Có thay đổi chưa lưu. Lưu từng phần trước khi xuất bản.' : '✓ Nội dung đang hiển thị đã được lưu.'}</p>
    <Notice error={error} success={success} reload={safeReload} />
    <section className="card"><h2>Thông tin kỳ thi</h2><form onSubmit={e => { e.preventDefault(); void mutate('metadata'); }}><fieldset disabled={published || busy}><MetadataFields value={form} onChange={setForm} />{!published && <button className="primary" type="submit" disabled={!metaDirty || busy}>Lưu thông tin</button>}</fieldset></form></section>
    <section className="card"><div className="section-heading"><h2>Câu hỏi ({questions.length})</h2>{questionDirty && <span className="dirty">Chưa lưu câu hỏi</span>}</div>
      <form onSubmit={e => { e.preventDefault(); void mutate('questions'); }}><QuestionEditor value={questions} onChange={setQuestions} readOnly={published} busy={busy} />
        {!published && <div className="save-bar"><button className="primary" type="submit" disabled={busy || !questionDirty}>Lưu toàn bộ câu hỏi</button><span>Không tự động lưu khi nhập.</span></div>}</form></section>
    <Enrollments id={saved.id} published={published} /></>;
}

function StudentDetail({ id, navigate }) {
  const load = useLoad(`${studentPath}/${id}`);
  const [checked, setChecked] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState();
  const [success, setSuccess] = useState('');
  const [acceptedAt, setAcceptedAt] = useState(null);
  function reload() { setChecked(false); load.reload(); }
  async function submit(e) {
    e.preventDefault(); if (busy || !checked || !load.data) return;
    setBusy(true); setError(undefined); setSuccess('');
    try {
      const result = await api(`${studentPath}/${id}/consent`, 'POST', { accepted: true, consentVersion: load.data.consentVersion });
      setAcceptedAt(result.acceptedAt); setSuccess('Đã ghi nhận đồng ý giám sát.'); reload();
    } catch (e) {
      setError(e);
      if (e instanceof ApiError && e.status === 409) { reload(); setSuccess(''); }
    } finally { setBusy(false); }
  }
  return <><button className="back" onClick={() => navigate(studentPath)}>← Kỳ thi đã ghi danh</button><Notice error={load.error} reload={reload} />
    {load.busy && <p role="status">Đang tải chi tiết…</p>}{load.data && <><p className="eyebrow">KỲ THI #{load.data.id}</p><h1>{load.data.title}</h1><section className="card"><p className="prewrap">{load.data.description || 'Chưa có mô tả.'}</p>
      <dl className="facts"><div><dt>Thời lượng</dt><dd>{load.data.durationMinutes} phút</dd></div><div><dt>Số câu hỏi</dt><dd>{load.data.questionCount}</dd></div><div><dt>Trạng thái từ máy chủ</dt><dd>{availabilityLabel[load.data.availability]}</dd></div><div><dt>Mở thi</dt><dd>{dateText(load.data.opensAt)}</dd></div><div><dt>Đóng thi</dt><dd>{dateText(load.data.closesAt)}</dd></div><div><dt>Máy chủ cập nhật lúc</dt><dd>{dateText(load.data.serverTime)}</dd></div></dl><p className="muted">Múi giờ hiển thị: {timezone}. <button onClick={reload}>Cập nhật trạng thái</button></p></section>
      <section className="card"><p className="eyebrow">PHIÊN BẢN {load.data.consentVersion}</p><h2>Đồng ý giám sát</h2><div className="consent prewrap">{load.data.consentText}</div>
        {load.data.consentAccepted ? <div className="notice success" role="status">✓ Bạn đã đồng ý giám sát.{acceptedAt && <p>Thời điểm ghi nhận: {dateText(acceptedAt)}</p>}</div> : <form onSubmit={submit}><fieldset disabled={busy || load.data.availability === 'CLOSED'}><label className="check-label"><input type="checkbox" checked={checked} onChange={e => setChecked(e.target.checked)} />Tôi đã đọc và đồng ý với nội dung giám sát ở trên.</label><button className="primary" type="submit" disabled={!checked || busy || load.data.availability === 'CLOSED'}>{busy ? 'Đang gửi…' : 'Xác nhận đồng ý'}</button></fieldset>{load.data.availability === 'CLOSED' && <p>Kỳ thi đã đóng, không thể ghi nhận đồng ý.</p>}</form>}
        <Notice error={error} success={success} reload={reload} />{error instanceof ApiError && error.status === 409 && <p role="alert">Chi tiết đã được yêu cầu tải lại. Hãy đọc lại nội dung và xác nhận lại nếu kỳ thi còn cho phép.</p>}</section>
      <AttemptAction exam={load.data} navigate={navigate} refreshExam={reload} /></>}</>;
}

function App() {
  const [user, setUser] = useState(null);
  const [restoring, setRestoring] = useState(true);
  const [restoreError, setRestoreError] = useState();
  const [notice, setNotice] = useState('');
  const [route, setRoute] = useState(() => location.hash.slice(1) || '/');
  const routeRef = useRef(route);
  const dirtyRef = useRef(false);
  const [logoutBusy, setLogoutBusy] = useState(false);
  const [logoutError, setLogoutError] = useState();
  const onDirty = useCallback((value) => { dirtyRef.current = value; }, []);
  const navigate = useCallback((next) => {
    if (next === routeRef.current) return;
    if (dirtyRef.current && !window.confirm('Có thay đổi chưa lưu. Rời trang và bỏ các thay đổi này?')) return;
    dirtyRef.current = false; routeRef.current = next; setRoute(next); location.hash = next;
  }, []);
  const restore = useCallback(async () => {
    setRestoring(true); setRestoreError(undefined);
    try { setUser(await api('/auth/me', 'GET', undefined, true)); }
    catch (e) { if (e instanceof ApiError && e.status === 401) setUser(null); else setRestoreError(e); }
    finally { setRestoring(false); }
  }, []);
  useEffect(() => { void restore(); }, [restore]);
  useEffect(() => {
    const hash = () => {
      const next = location.hash.slice(1) || '/';
      if (next !== routeRef.current) {
        if (dirtyRef.current && !window.confirm('Có thay đổi chưa lưu. Rời trang và bỏ các thay đổi này?')) { location.hash = routeRef.current; return; }
        dirtyRef.current = false; routeRef.current = next; setRoute(next);
      }
    };
    const unload = (e) => { if (dirtyRef.current) { e.preventDefault(); e.returnValue = ''; } };
    const expired = () => { dirtyRef.current = false; setUser(null); setNotice('Phiên đăng nhập đã hết hiệu lực. Vui lòng đăng nhập lại.'); routeRef.current = '/login'; setRoute('/login'); location.hash = '/login'; };
    window.addEventListener('hashchange', hash); window.addEventListener('beforeunload', unload); window.addEventListener('session-expired', expired);
    return () => { window.removeEventListener('hashchange', hash); window.removeEventListener('beforeunload', unload); window.removeEventListener('session-expired', expired); };
  }, []);
  function loggedIn(account) { setNotice(''); setUser(account); navigate(account.role === 'STUDENT' ? studentPath : teacherPath); }
  async function logout() {
    if (logoutBusy || (dirtyRef.current && !window.confirm('Có thay đổi chưa lưu. Đăng xuất và bỏ các thay đổi này?'))) return;
    setLogoutBusy(true); setLogoutError(undefined);
    try {
      await api('/auth/logout', 'POST'); dirtyRef.current = false; setUser(null); navigate('/login'); setNotice('Đã đăng xuất.');
      await refreshCsrf();
    } catch (e) { setLogoutError(e); } finally { setLogoutBusy(false); }
  }
  let content;
  const home = user?.role === 'STUDENT' ? studentPath : teacherPath;
  if (restoring) content = <p role="status">Đang kiểm tra phiên đăng nhập…</p>;
  else if (restoreError) content = <Notice error={restoreError} reload={() => void restore()} />;
  else if (!user) content = route === '/register'
    ? <Register navigate={navigate} onRegistered={account => { setNotice(`Đã tạo tài khoản ${account.email}. Bạn có thể đăng nhập ngay.`); navigate('/login'); }} />
    : <Login onLogin={loggedIn} navigate={navigate} />;
  else {
    const path = route === '/' || route === '/login' || route === '/register' ? home : route;
    const teacher = user.role === 'TEACHER' || user.role === 'ADMIN';
    if ((path.startsWith(teacherPath) && !teacher) || ((path.startsWith(studentPath) || path.startsWith('/student/attempts')) && user.role !== 'STUDENT')) content = <section className="card"><h1>Không có quyền truy cập</h1><p>Vai trò của bạn không được phép mở khu vực này.</p><button onClick={() => navigate(home)}>Về kỳ thi của tôi</button></section>;
    else if (path === home) content = <ExamList key={home} student={!teacher} navigate={navigate} />;
    else if (path === `${teacherPath}/new` && teacher) content = <CreateExam navigate={navigate} onDirty={onDirty} />;
    else if (/^\/teacher\/exams\/[1-9]\d*$/.test(path) && teacher) content = <TeacherDetail key={path} id={path.split('/').pop()} navigate={navigate} onDirty={onDirty} />;
    else if (/^\/student\/exams\/[1-9]\d*$/.test(path) && user.role === 'STUDENT') content = <StudentDetail key={path} id={path.split('/').pop()} navigate={navigate} />;
    else if (/^\/student\/attempts\/[1-9]\d*$/.test(path) && user.role === 'STUDENT') content = <AttemptPage key={path} id={path.split('/').pop()} navigate={navigate} />;
    else content = <section className="card"><h1>Không tìm thấy trang</h1><button onClick={() => navigate(home)}>Về danh sách kỳ thi</button></section>;
  }
  return <><a className="skip-link" href="#main" onClick={e => { e.preventDefault(); document.getElementById('main').focus(); }}>Đến nội dung chính</a><header><div className="header-inner"><button className="brand" onClick={() => navigate(user ? home : '/login')}><span className="brand-mark">K</span>Kỳ thi<span className="brand-note">Kiểm thử luồng</span></button>
    {user && <div className="account"><div><strong>{user.displayName}</strong><span>{roleLabel[user.role]} · ID {user.id} · {user.email}</span></div><button disabled={logoutBusy} onClick={() => void logout()}>{logoutBusy ? 'Đang đăng xuất…' : 'Đăng xuất'}</button></div>}</div></header>
    <main id="main" tabIndex={-1}>{notice && <div className="notice" role="status">{notice}</div>}<Notice error={logoutError} />{content}</main><footer>Kỳ thi · Không gian kiểm thử tối thiểu</footer></>;
}
createRoot(document.getElementById('root')).render(<App />);
