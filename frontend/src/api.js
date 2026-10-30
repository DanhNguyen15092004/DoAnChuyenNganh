export class ApiError extends Error {
  constructor(status, problem) {
    super(status === 403 ? 'Yêu cầu bị từ chối. Quyền truy cập hoặc token bảo vệ có thể không hợp lệ. Token đã được yêu cầu làm mới; hãy chủ động thử lại.'
      : status === 404 ? 'Không tìm thấy tài nguyên hoặc bạn không có quyền truy cập.'
      : status >= 500 ? 'Máy chủ gặp lỗi. Hãy chủ động thử lại.'
      : problem.detail || problem.title || 'Không thể thực hiện yêu cầu.');
    this.status = status;
    this.problem = problem;
  }
}
let csrf = null;
export async function refreshCsrf() {
  csrf = null;
  let response;
  try { response = await fetch('/api/v1/auth/csrf', { credentials: 'include', cache: 'no-store' }); }
  catch { throw new Error('Không kết nối được máy chủ để lấy token bảo vệ. Hãy kiểm tra kết nối và thử lại.'); }
  if (!response.ok) throw new Error('Không thể lấy token bảo vệ. Kiểm tra backend và thử lại.');
  const token = await response.json();
  if (typeof token.headerName !== 'string' || typeof token.token !== 'string') {
    throw new Error('Phản hồi token bảo vệ không hợp lệ. Hãy thử lại.');
  }
  csrf = token;
}
export async function api(path, method = 'GET', body, publicRequest = false) {
  const headers = { Accept: 'application/json, application/problem+json' };
  if (method !== 'GET') {
    if (!csrf) await refreshCsrf();
    if (!csrf) throw new Error('Chưa có token bảo vệ. Hãy thử lại.');
    headers[csrf.headerName] = csrf.token;
  }
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let response;
  try {
    response = await fetch(`/api/v1${path}`, {
      method, headers, credentials: 'include', cache: 'no-store',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new Error('Mất kết nối. Thao tác có thể đã tới máy chủ. Kiểm tra bằng cách tải lại trước khi chủ động gửi lại.');
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    if (response.status === 401 && !publicRequest) window.dispatchEvent(new Event('session-expired'));
    if (response.status === 403) await refreshCsrf().catch(() => { /* Next manual action will fetch again. */ });
    throw new ApiError(response.status, problem);
  }
  if (response.status === 204) return undefined;
  try { return await response.json(); }
  catch { throw new Error('Phản hồi máy chủ không hợp lệ. Tải lại để kiểm tra kết quả trước khi gửi lại thao tác.'); }
}
