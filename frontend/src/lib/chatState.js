// All chat state lives in one reducer. The merge helpers are pure so they can be tested.

/** Status only moves forward; PENDING/FAILED are local-only states before the server confirms. */
export const STATUS_RANK = { FAILED: -1, PENDING: 0, SENT: 1, DELIVERED: 2, READ: 3 }
const isServerStatus = (s) => s === 'SENT' || s === 'DELIVERED' || s === 'READ'

function laterStatus(a, b) {
  if (!a) return b
  if (!b) return a
  if (isServerStatus(a) && isServerStatus(b)) return STATUS_RANK[a] >= STATUS_RANK[b] ? a : b
  return isServerStatus(b) ? b : isServerStatus(a) ? a : b
}

const sameMessage = (a, b) =>
  (a.id != null && a.id === b.id) ||
  (a.clientMessageId && a.clientMessageId === b.clientMessageId && a.senderId === b.senderId)

/** Confirmed messages by seq; unconfirmed (no seq yet) after them, in the order they were written. */
export function sortMessages(list) {
  return [...list].sort((a, b) => {
    if (a.seq != null && b.seq != null) return a.seq - b.seq
    if (a.seq != null) return -1
    if (b.seq != null) return 1
    return new Date(a.createdAt) - new Date(b.createdAt)
  })
}

/**
 * Inserts or updates one message. Deduplicates by server id, or by clientMessageId for
 * our own optimistic copy, so a message that arrives via both REST and WebSocket shows once.
 * @returns {{ list: Array, isNew: boolean }}
 */
export function mergeMessage(list = [], incoming) {
  const i = list.findIndex((m) => sameMessage(m, incoming))
  if (i === -1) return { list: sortMessages([...list, incoming]), isNew: true }
  const existing = list[i]
  const merged = {
    ...existing,
    ...incoming,
    status: laterStatus(existing.status, incoming.status),
    localPreview: incoming.imageUrl ? undefined : existing.localPreview,
  }
  const next = [...list]
  next[i] = merged
  return { list: sortMessages(next), isNew: false }
}

export function mergeMessages(list = [], incoming = []) {
  return incoming.reduce((acc, m) => mergeMessage(acc, m).list, list)
}

export const initialChatState = {
  me: null,
  blocked: {}, // userId -> profile
  conversations: {},
  messages: {},
  hasMore: {},
  activeId: null,
}

function updateConversation(state, id, fn) {
  const conv = state.conversations[id]
  if (!conv) return state
  return { ...state, conversations: { ...state.conversations, [id]: fn(conv) } }
}

function withLastMessage(conv, msg) {
  if (msg.seq == null) return conv
  const current = conv.lastMessage
  if (current && current.seq > msg.seq) return conv
  if (current && current.id === msg.id) return { ...conv, lastMessage: { ...current, ...msg, status: laterStatus(current.status, msg.status) } }
  return { ...conv, lastMessage: msg, lastActivityAt: msg.createdAt }
}

export function chatReducer(state, action) {
  switch (action.type) {
    case 'me':
      return { ...state, me: action.me }

    case 'conversationsLoaded': {
      const conversations = {}
      for (const c of action.conversations) conversations[c.id] = { ...c, unreadCount: c.unreadCount ?? 0 }
      const activeId = conversations[state.activeId] ? state.activeId : null
      return { ...state, conversations, activeId }
    }

    case 'conversationUpserted': {
      const c = action.conversation
      const prev = state.conversations[c.id]
      const unreadCount = c.unreadCount ?? prev?.unreadCount ?? 0
      let merged = { ...prev, ...c, unreadCount }
      if (prev?.lastMessage && c.lastMessage && prev.lastMessage.seq > c.lastMessage.seq) merged.lastMessage = prev.lastMessage
      return { ...state, conversations: { ...state.conversations, [c.id]: merged } }
    }

    case 'conversationRemoved': {
      // Drop its messages too: if the chat comes back (e.g. after "delete chat"), only what the
      // server returns then may show.
      const { [action.id]: _gone, ...rest } = state.conversations
      const { [action.id]: _msgs, ...messages } = state.messages
      const { [action.id]: _more, ...hasMore } = state.hasMore
      return { ...state, conversations: rest, messages, hasMore, activeId: state.activeId === action.id ? null : state.activeId }
    }

    case 'setActive':
      return { ...state, activeId: action.id }

    case 'historyLoaded': {
      const { conversationId, messages, older, pageSize } = action
      const list = mergeMessages(state.messages[conversationId], messages)
      const hasMore = { ...state.hasMore }
      if (older || hasMore[conversationId] === undefined) hasMore[conversationId] = messages.length >= pageSize
      return { ...state, messages: { ...state.messages, [conversationId]: list }, hasMore }
    }

    case 'messageUpsert': {
      const msg = action.message
      const { list, isNew } = mergeMessage(state.messages[msg.conversationId], msg)
      let next = { ...state, messages: { ...state.messages, [msg.conversationId]: list } }
      const stored = list.find((m) => sameMessage(m, msg))
      next = updateConversation(next, msg.conversationId, (c) => {
        let updated = withLastMessage(c, stored)
        if (isNew && action.countAsUnread) updated = { ...updated, unreadCount: (c.unreadCount || 0) + 1 }
        return updated
      })
      return next
    }

    case 'messageStatus': {
      const { conversationId, clientMessageId, status } = action
      const list = (state.messages[conversationId] || []).map((m) =>
        m.clientMessageId === clientMessageId && m.senderId === state.me?.id && m.id == null ? { ...m, status } : m)
      return { ...state, messages: { ...state.messages, [conversationId]: list } }
    }

    case 'receipts': {
      let next = state
      for (const change of action.changes) {
        const list = next.messages[change.conversationId]
        if (list) {
          const updated = list.map((m) => (m.id === change.messageId ? { ...m, status: laterStatus(m.status, change.status) } : m))
          next = { ...next, messages: { ...next.messages, [change.conversationId]: updated } }
        }
        next = updateConversation(next, change.conversationId, (c) =>
          c.lastMessage?.id === change.messageId
            ? { ...c, lastMessage: { ...c.lastMessage, status: laterStatus(c.lastMessage.status, change.status) } }
            : c)
      }
      return next
    }

    case 'blocksLoaded':
      return { ...state, blocked: Object.fromEntries(action.users.map((u) => [u.id, u])) }

    case 'blocked':
      return { ...state, blocked: { ...state.blocked, [action.user.id]: action.user } }

    case 'unblocked': {
      const { [action.id]: _gone, ...rest } = state.blocked
      return { ...state, blocked: rest }
    }

    case 'readLocally':
      return updateConversation(state, action.id, (c) => ({ ...c, unreadCount: 0 }))

    case 'profileUpdated': {
      const p = action.profile
      const conversations = {}
      for (const [id, c] of Object.entries(state.conversations)) {
        conversations[id] = c.members.some((m) => m.id === p.id)
          ? { ...c, members: c.members.map((m) => (m.id === p.id ? { ...m, ...p } : m)) }
          : c
      }
      const me = state.me && state.me.id === p.id ? { ...state.me, displayName: p.displayName, avatarUrl: p.avatarUrl } : state.me
      return { ...state, conversations, me }
    }

    default:
      return state
  }
}

/** My status in a conversation: ACCEPTED, or PENDING for a request / invite I haven't answered. */
export const myStatus = (conv, meId) => conv?.members.find((m) => m.id === meId)?.status ?? 'ACCEPTED'
export const isRequest = (conv, meId) => myStatus(conv, meId) === 'PENDING'

/** Conversations newest first, split into accepted chats and incoming requests. */
export function sortedConversations(state) {
  const all = Object.values(state.conversations).sort((a, b) => new Date(b.lastActivityAt) - new Date(a.lastActivityAt))
  const meId = state.me?.id
  return { chats: all.filter((c) => !isRequest(c, meId)), requests: all.filter((c) => isRequest(c, meId)) }
}

export const maxSeq = (list = []) => list.reduce((max, m) => (m.seq != null && m.seq > max ? m.seq : max), 0)
