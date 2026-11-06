// Thin REST client. Every call goes to the API gateway (same origin).

export class ApiError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

let authToken = null
let onUnauthorized = () => {}

export function setAuth(token, unauthorizedHandler) {
  authToken = token
  if (unauthorizedHandler) onUnauthorized = unauthorizedHandler
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

export async function request(path, { method = 'GET', body, form, headers = {} } = {}) {
  const h = { ...headers }
  if (authToken) h.Authorization = `Bearer ${authToken}`
  let payload
  if (form) payload = form
  else if (body !== undefined) {
    h['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }

  let res
  try {
    res = await fetch(path, { method, headers: h, body: payload })
  } catch {
    throw new ApiError(0, 'Network error. Check your connection.')
  }
  if (res.status === 204) return null
  const data = await res.json().catch(() => null)
  if (!res.ok) {
    if (res.status === 401 && authToken) onUnauthorized()
    if (res.status === 429) throw new ApiError(429, 'Too many attempts. Wait a moment and try again.')
    throw new ApiError(res.status, data?.message || res.statusText || 'Request failed')
  }
  return data
}

/** Network errors, 429 and 5xx are worth retrying; 4xx are not. */
export const isRetryable = (e) => e instanceof ApiError && (e.status === 0 || e.status === 429 || e.status >= 500)

/**
 * Exponential backoff with jitter. Only safe for idempotent calls: message sends are,
 * because the same clientMessageId is reused on every attempt.
 */
export async function withRetry(fn, { attempts = 4, baseMs = 500, wait = sleep } = {}) {
  for (let i = 0; ; i++) {
    try {
      return await fn()
    } catch (e) {
      if (!isRetryable(e) || i >= attempts - 1) throw e
      await wait(baseMs * 2 ** i + Math.random() * 200)
    }
  }
}

const post = (path, body, headers) => request(path, { method: 'POST', body, headers })

/**
 * Uploads a file straight to object storage with a presigned URL from the backend, then
 * returns the upload key to attach. The file never passes through our servers.
 * @param getTicket () => Promise<{ uploadUrl, method, headers, key }>
 */
export async function uploadFile(getTicket, file) {
  const ticket = await withRetry(getTicket)
  await withRetry(async () => {
    let res
    try {
      res = await fetch(ticket.uploadUrl, { method: ticket.method || 'PUT', headers: ticket.headers, body: file })
    } catch {
      throw new ApiError(0, 'Upload failed. Check your connection.')
    }
    if (!res.ok) throw new ApiError(res.status >= 500 ? res.status : 400, `Upload failed (${res.status})`)
  })
  return ticket.key
}

export const api = {
  register: (username, displayName, password) => post('/api/auth/register', { username, displayName, password }),
  login: (username, password) => post('/api/auth/login', { username, password }),

  me: () => request('/api/users/me'),
  avatarUploadUrl: (file) => post('/api/users/me/avatar/upload-url', { contentType: file.type, size: file.size }),
  setAvatar: (uploadKey) => request('/api/users/me/avatar', { method: 'PUT', body: { uploadKey } }),
  updateProfile: (displayName, version) => request('/api/users/me', { method: 'PATCH', body: { displayName, version } }),
  removeAvatar: () => request('/api/users/me/avatar', { method: 'DELETE' }),
  searchUsers: (q) => request(`/api/users/search?q=${encodeURIComponent(q)}`),

  conversations: () => request('/api/conversations'),
  conversation: (id) => request(`/api/conversations/${id}`),
  openDirect: (userId) => post('/api/conversations/direct', { userId }),
  createGroup: (name, memberIds, idempotencyKey) =>
    post('/api/conversations/group', { name, memberIds }, { 'Idempotency-Key': idempotencyKey }),
  addMembers: (id, userIds) => post(`/api/conversations/${id}/members`, { userIds }),
  deleteConversation: (id) => request(`/api/conversations/${id}`, { method: 'DELETE' }),
  leave: (id) => request(`/api/conversations/${id}/members/me`, { method: 'DELETE' }),

  messages: (id, { beforeSeq, afterSeq, limit = 50 } = {}) => {
    const q = new URLSearchParams({ limit })
    if (beforeSeq != null) q.set('beforeSeq', beforeSeq)
    if (afterSeq != null) q.set('afterSeq', afterSeq)
    return request(`/api/conversations/${id}/messages?${q}`)
  },
  sendMessage: (id, clientMessageId, content, uploadKey) =>
    post(`/api/conversations/${id}/messages`, { clientMessageId, content, uploadKey }),
  attachmentUploadUrl: (id, file) =>
    post(`/api/conversations/${id}/attachments/upload-url`, { contentType: file.type, size: file.size }),
  accept: (id) => post(`/api/conversations/${id}/accept`),
  reject: (id) => post(`/api/conversations/${id}/reject`),

  blocks: () => request('/api/blocks'),
  block: (userId) => request(`/api/blocks/${userId}`, { method: 'PUT' }),
  unblock: (userId) => request(`/api/blocks/${userId}`, { method: 'DELETE' }),
  markRead: (id) => post(`/api/conversations/${id}/read`),
  delivered: (messageIds) => post('/api/messages/delivered', { messageIds }),

  notifications: () => request('/api/notifications?limit=30'),
  notificationsReadAll: () => post('/api/notifications/read-all'),
  notificationsReadConversation: (conversationId) => post(`/api/notifications/read?conversationId=${conversationId}`),
}
