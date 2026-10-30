export const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone;
export function dateText(value) {
  return value ? new Date(value).toLocaleString('vi-VN', { timeZoneName: 'short' }) : '—';
}
export function toLocalInput(instant) {
  const date = new Date(instant);
  const pad = (n, length = 2) => String(n).padStart(length, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}.${pad(date.getMilliseconds(), 3)}`;
}
export function toInstant(local) {
  const parsed = new Date(local);
  if (!local || !Number.isFinite(parsed.getTime())) throw new Error('Nhập thời gian hợp lệ.');
  // Detect nonexistent local wall time (for example a daylight-saving time jump).
  if (toLocalInput(parsed.toISOString()).slice(0, 16) !== local.slice(0, 16)) throw new Error('Giờ này không tồn tại trong múi giờ trình duyệt.');
  return parsed.toISOString();
}
export function metadataError(data) {
  if (!data.title.trim() || data.title.length > 200) return 'Tiêu đề cần 1–200 ký tự và không chỉ gồm khoảng trắng.';
  if ((data.description?.length || 0) > 5000) return 'Mô tả tối đa 5.000 ký tự.';
  if (!Number.isInteger(data.durationMinutes) || data.durationMinutes < 1 || data.durationMinutes > 300) return 'Thời lượng là số nguyên từ 1 đến 300 phút.';
  if (Date.parse(data.closesAt) - Date.parse(data.opensAt) < data.durationMinutes * 60000) return 'Khoảng mở/đóng phải dương và đủ thời lượng kỳ thi.';
  return null;
}
export function questionPayload(questions) {
  return questions.map((q, i) => ({ content: q.content, position: i + 1,
    options: q.options.map((o, j) => ({ content: o.content, position: j + 1, correct: o.correct })) }));
}
export function questionsError(questions) {
  if (questions.length > 100) return 'Tối đa 100 câu hỏi.';
  for (const [i, q] of questions.entries()) {
    if (!q.content.trim() || q.content.length > 5000) return `Câu ${i + 1}: nội dung cần 1–5.000 ký tự.`;
    if (q.options.length < 2 || q.options.length > 6) return `Câu ${i + 1}: cần 2–6 lựa chọn.`;
    if (q.options.some(o => !o.content.trim() || o.content.length > 2000)) return `Câu ${i + 1}: lựa chọn cần 1–2.000 ký tự.`;
    if (q.options.filter(o => o.correct).length !== 1) return `Câu ${i + 1}: chọn đúng một đáp án đúng.`;
  }
  return null;
}
