import { describe, expect, it } from 'vitest'
import { chatReducer, initialChatState, maxSeq, mergeMessage, mergeMessages, sortMessages, sortedConversations } from './chatState.js'
import { withRetry, ApiError } from './api.js'

const msg = (o) => ({ conversationId: 1, senderId: 1, type: 'TEXT', content: 'x', createdAt: '2026-01-01T00:00:00Z', ...o })

describe('mergeMessage', () => {
  it('replaces the optimistic copy with the server copy (same clientMessageId)', () => {
    const pending = msg({ clientMessageId: 'c1', status: 'PENDING' })
    const { list } = mergeMessage([pending], msg({ id: 10, seq: 1, clientMessageId: 'c1', status: 'SENT' }))
    expect(list).toHaveLength(1)
    expect(list[0]).toMatchObject({ id: 10, seq: 1, status: 'SENT' })
  })

  it('dedupes a message that arrives over REST and WebSocket', () => {
    const m = msg({ id: 5, seq: 3, clientMessageId: 'c', status: 'SENT' })
    const list = mergeMessages([], [m, m, { ...m }])
    expect(list).toHaveLength(1)
  })

  it('never moves a status backwards', () => {
    const read = msg({ id: 5, seq: 1, status: 'READ' })
    const { list } = mergeMessage([read], msg({ id: 5, seq: 1, status: 'SENT' }))
    expect(list[0].status).toBe('READ')
  })

  it('orders by seq and keeps unconfirmed messages last', () => {
    const list = sortMessages([
      msg({ clientMessageId: 'p', createdAt: '2026-01-01T00:00:00Z' }),
      msg({ id: 2, seq: 2 }),
      msg({ id: 1, seq: 1 }),
    ])
    expect(list.map((m) => m.seq ?? 'pending')).toEqual([1, 2, 'pending'])
  })
})

describe('chatReducer', () => {
  const base = chatReducer(
    chatReducer(initialChatState, { type: 'me', me: { id: 1 } }),
    { type: 'conversationsLoaded', conversations: [{ id: 1, type: 'DIRECT', members: [], unreadCount: 0, lastActivityAt: '2026-01-01T00:00:00Z' }] },
  )

  it('counts unread only once per message', () => {
    const incoming = msg({ id: 7, seq: 1, senderId: 2, status: 'SENT' })
    let s = chatReducer(base, { type: 'messageUpsert', message: incoming, countAsUnread: true })
    s = chatReducer(s, { type: 'messageUpsert', message: incoming, countAsUnread: true })
    expect(s.conversations[1].unreadCount).toBe(1)
    expect(s.conversations[1].lastMessage.id).toBe(7)
  })

  it('applies receipts to messages and the conversation preview', () => {
    let s = chatReducer(base, { type: 'messageUpsert', message: msg({ id: 9, seq: 1, status: 'SENT' }) })
    s = chatReducer(s, { type: 'receipts', changes: [{ conversationId: 1, messageId: 9, status: 'DELIVERED' }] })
    expect(s.messages[1][0].status).toBe('DELIVERED')
    expect(s.conversations[1].lastMessage.status).toBe('DELIVERED')
  })

  it('does not let an older message replace the preview', () => {
    let s = chatReducer(base, { type: 'messageUpsert', message: msg({ id: 2, seq: 2 }) })
    s = chatReducer(s, { type: 'messageUpsert', message: msg({ id: 1, seq: 1 }) })
    expect(s.conversations[1].lastMessage.seq).toBe(2)
    expect(maxSeq(s.messages[1])).toBe(2)
  })
})

describe('requests', () => {
  it('splits chats from incoming requests by my member status', () => {
    let s = chatReducer(initialChatState, { type: 'me', me: { id: 1 } })
    s = chatReducer(s, { type: 'conversationsLoaded', conversations: [
      { id: 1, type: 'DIRECT', members: [{ id: 1, status: 'ACCEPTED' }, { id: 2, status: 'PENDING' }], lastActivityAt: '2026-01-01T00:00:00Z' },
      { id: 2, type: 'DIRECT', members: [{ id: 1, status: 'PENDING' }, { id: 3, status: 'ACCEPTED' }], lastActivityAt: '2026-01-02T00:00:00Z' },
    ] })
    const { chats, requests } = sortedConversations(s)
    expect(chats.map((c) => c.id)).toEqual([1]) // a request *I* sent is still my chat
    expect(requests.map((c) => c.id)).toEqual([2])
  })

  it('removing a conversation forgets its messages', () => {
    let s = chatReducer(initialChatState, { type: 'me', me: { id: 1 } })
    s = chatReducer(s, { type: 'conversationsLoaded', conversations: [{ id: 3, type: 'DIRECT', members: [], lastActivityAt: '2026-01-01T00:00:00Z' }] })
    s = chatReducer(s, { type: 'messageUpsert', message: { id: 1, seq: 1, conversationId: 3, senderId: 2, createdAt: '2026-01-01T00:00:00Z' } })
    s = chatReducer(s, { type: 'conversationRemoved', id: 3 })
    expect(s.conversations[3]).toBeUndefined()
    expect(s.messages[3]).toBeUndefined()
  })

  it('tracks blocked users', () => {
    let s = chatReducer(initialChatState, { type: 'blocked', user: { id: 5, displayName: 'Eve' } })
    expect(s.blocked[5]).toBeTruthy()
    s = chatReducer(s, { type: 'unblocked', id: 5 })
    expect(s.blocked[5]).toBeUndefined()
  })
})

describe('withRetry', () => {
  const noWait = () => Promise.resolve()

  it('retries network and 5xx errors, then succeeds', async () => {
    let calls = 0
    const result = await withRetry(async () => {
      calls++
      if (calls < 3) throw new ApiError(calls === 1 ? 0 : 503, 'flaky')
      return 'ok'
    }, { wait: noWait })
    expect(result).toBe('ok')
    expect(calls).toBe(3)
  })

  it('does not retry client errors', async () => {
    let calls = 0
    await expect(withRetry(async () => { calls++; throw new ApiError(400, 'bad') }, { wait: noWait })).rejects.toThrow('bad')
    expect(calls).toBe(1)
  })
})
