import { useCallback, useEffect, useMemo, useReducer, useRef, useState } from 'react'
import { api, isRetryable, uploadFile, withRetry } from '../lib/api.js'
import { chatReducer, initialChatState, isRequest, maxSeq, sortedConversations } from '../lib/chatState.js'
import { newId } from '../lib/format.js'
import { connectSocket } from '../lib/realtime.js'
import Avatar from './Avatar.jsx'
import ChatView, { EmptyChat } from './ChatView.jsx'
import ConversationList from './ConversationList.jsx'
import { GroupInfoFlyout, NewChatFlyout, NotificationsFlyout, ProfileFlyout } from './Flyouts.jsx'
import { BellIcon, ChatIcon, GroupIcon, MoonIcon, PlusPersonIcon, SunIcon } from './Icons.jsx'
import { Lightbox, Toasts } from './Overlays.jsx'

const PAGE = 50

/**
 * Owns all chat state and talks to the backend:
 *  - REST for history and writes, WebSockets for live updates.
 *  - Sends are optimistic and idempotent: each gets a clientMessageId that's reused on
 *    every retry, so retries can't create duplicates. Images go straight to object storage
 *    via a presigned URL; the upload key is remembered so a retry doesn't upload again.
 *  - Chats someone else started are requests until accepted: no read receipts are sent for them.
 *  - After any reconnect it re-syncs (conversation list + missed messages by seq) and
 *    re-sends messages that failed while offline.
 */
export default function ChatApp({ token, initialUser, onUserChange, onSignOut, prefs, setPrefs }) {
  const [state, dispatch] = useReducer(chatReducer, { ...initialChatState, me: initialUser })
  const [panel, setPanel] = useState(null) // direct | group | notifications | profile | info
  const [notifs, setNotifs] = useState({ items: [], unreadCount: 0 })
  const [toasts, setToasts] = useState([])
  const [online, setOnline] = useState(false)
  const [loading, setLoading] = useState(true)
  const [lightbox, setLightbox] = useState(null)
  const [tab, setTab] = useState('chats') // chats | requests

  const stateRef = useRef(state)
  stateRef.current = state
  const files = useRef(new Map()) // clientMessageId -> { file, uploadKey }, kept until the message is confirmed
  const me = state.me

  // ---------- toasts ----------
  const toast = useCallback((t) => {
    const id = newId()
    setToasts((ts) => [...ts.slice(-2), { ...t, id }])
    setTimeout(() => setToasts((ts) => ts.filter((x) => x.id !== id)), t.kind === 'error' ? 6000 : 5000)
  }, [])
  const showError = useCallback((e) => toast({ kind: 'error', title: 'Something went wrong', body: e.message }), [toast])

  // ---------- notifications ----------
  const loadNotifications = useCallback(() => api.notifications().then(setNotifs).catch(() => {}), [])

  // ---------- receipts (delivered / read) ----------
  const isViewing = (cid) => stateRef.current.activeId === cid && document.visibilityState === 'visible'
  const accepted = (cid) => {
    const c = stateRef.current.conversations[cid]
    return c && !isRequest(c, stateRef.current.me.id)
  }

  const ackQueue = useRef(new Set())
  const ackTimer = useRef()
  const ackDelivered = useCallback((messageId) => {
    ackQueue.current.add(messageId)
    clearTimeout(ackTimer.current)
    ackTimer.current = setTimeout(() => {
      const ids = [...ackQueue.current]
      ackQueue.current.clear()
      withRetry(() => api.delivered(ids)).catch(() => {}) // safe to repeat; the server only fills empty timestamps
    }, 200)
  }, [])

  const readTimers = useRef({})
  const markRead = useCallback((cid) => {
    if (!accepted(cid)) return // no read receipts until you accept the request
    dispatch({ type: 'readLocally', id: cid })
    clearTimeout(readTimers.current[cid])
    readTimers.current[cid] = setTimeout(() => {
      withRetry(() => api.markRead(cid)).catch(() => {})
      api.notificationsReadConversation(cid).then(loadNotifications).catch(() => {})
    }, 250)
  }, [loadNotifications])

  // ---------- sending ----------
  const deliver = useCallback(async (m) => {
    try {
      let uploadKey
      if (m.type === 'IMAGE') {
        const entry = files.current.get(m.clientMessageId)
        if (!entry.uploadKey) entry.uploadKey = await uploadFile(() => api.attachmentUploadUrl(m.conversationId, entry.file), entry.file)
        uploadKey = entry.uploadKey
      }
      const saved = await withRetry(() => api.sendMessage(m.conversationId, m.clientMessageId, m.content, uploadKey))
      files.current.delete(m.clientMessageId)
      dispatch({ type: 'messageUpsert', message: saved })
    } catch (e) {
      dispatch({ type: 'messageStatus', conversationId: m.conversationId, clientMessageId: m.clientMessageId, status: 'FAILED' })
      if (!isRetryable(e)) showError(e)
    }
  }, [showError])

  const queue = (conversationId, fields) => {
    const m = {
      clientMessageId: newId(), conversationId, senderId: me.id, status: 'PENDING',
      createdAt: new Date().toISOString(), ...fields,
    }
    dispatch({ type: 'messageUpsert', message: m })
    deliver(m)
  }

  const sendText = (cid, text) => queue(cid, { type: 'TEXT', content: text })

  const sendImage = (cid, file, caption) => {
    const clientMessageId = newId()
    files.current.set(clientMessageId, { file })
    const m = {
      clientMessageId, conversationId: cid, senderId: me.id, status: 'PENDING', createdAt: new Date().toISOString(),
      type: 'IMAGE', content: caption || null, localPreview: URL.createObjectURL(file),
    }
    dispatch({ type: 'messageUpsert', message: m })
    deliver(m)
  }

  const retry = useCallback((m) => {
    dispatch({ type: 'messageStatus', conversationId: m.conversationId, clientMessageId: m.clientMessageId, status: 'PENDING' })
    deliver(m)
  }, [deliver])

  // ---------- loading / syncing ----------
  const openConversation = useCallback(async (cid) => {
    dispatch({ type: 'setActive', id: cid })
    setPanel(null)
    const conv = stateRef.current.conversations[cid]
    if (conv) setTab(isRequest(conv, stateRef.current.me.id) ? 'requests' : 'chats')
    // A group invite shows nothing until accepted; a direct request can be previewed.
    if (conv && conv.type === 'GROUP' && isRequest(conv, stateRef.current.me.id)) return
    const existing = stateRef.current.messages[cid]
    try {
      const page = existing?.length
        ? await api.messages(cid, { afterSeq: maxSeq(existing), limit: 100 }) // catch up on anything missed
        : await api.messages(cid, { limit: PAGE })
      dispatch({ type: 'historyLoaded', conversationId: cid, messages: page, older: false, pageSize: PAGE })
      markRead(cid)
    } catch (e) {
      showError(e)
    }
  }, [markRead, showError])

  const loadOlder = useCallback(async (cid) => {
    const list = stateRef.current.messages[cid] || []
    const first = list.find((m) => m.seq != null)
    if (!first) return
    try {
      const page = await api.messages(cid, { beforeSeq: first.seq, limit: PAGE })
      dispatch({ type: 'historyLoaded', conversationId: cid, messages: page, older: true, pageSize: PAGE })
    } catch (e) {
      showError(e)
    }
  }, [showError])

  const sync = useCallback(async () => {
    const conversations = await withRetry(() => api.conversations())
    dispatch({ type: 'conversationsLoaded', conversations })
    api.delivered([]).catch(() => {}) // everything we just synced has reached this device
    api.blocks().then((users) => dispatch({ type: 'blocksLoaded', users })).catch(() => {})
    loadNotifications()

    const active = stateRef.current.activeId
    if (active && conversations.some((c) => c.id === active && !(c.type === 'GROUP' && isRequest(c, stateRef.current.me.id)))) {
      const missed = await api.messages(active, { afterSeq: maxSeq(stateRef.current.messages[active]), limit: 100 })
      dispatch({ type: 'historyLoaded', conversationId: active, messages: missed, older: false, pageSize: PAGE })
      if (isViewing(active)) markRead(active)
    }
    // Messages that failed while we were offline get another go (same clientMessageId, so no duplicates).
    for (const list of Object.values(stateRef.current.messages)) {
      list.filter((m) => m.status === 'FAILED' && m.senderId === stateRef.current.me.id).forEach(retry)
    }
  }, [loadNotifications, markRead, retry])

  useEffect(() => {
    sync().catch(showError).finally(() => setLoading(false))
    api.me().then((u) => { dispatch({ type: 'me', me: u }); onUserChange(u) }).catch(() => {})
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // ---------- live updates ----------
  const handlers = useRef({})
  handlers.current = {
    message(m) {
      const s = stateRef.current
      const mine = m.senderId === s.me.id
      const viewing = isViewing(m.conversationId)
      if (!s.conversations[m.conversationId]) {
        api.conversation(m.conversationId).then((c) => dispatch({ type: 'conversationUpserted', conversation: c })).catch(() => {})
      }
      dispatch({ type: 'messageUpsert', message: m, countAsUnread: !mine && !viewing })
      if (!mine) {
        ackDelivered(m.id)
        if (viewing) markRead(m.conversationId)
      }
    },
    receipts: (changes) => dispatch({ type: 'receipts', changes }),
    conversation(update) {
      if (update.action === 'REMOVED') dispatch({ type: 'conversationRemoved', id: update.conversationId })
      else dispatch({ type: 'conversationUpserted', conversation: update.conversation })
    },
    user: (profile) => dispatch({ type: 'profileUpdated', profile }),
    notification(n) {
      setNotifs((ns) => ({ items: [n, ...ns.items.filter((i) => i.id !== n.id)].slice(0, 30), unreadCount: ns.unreadCount + 1 }))
      if (n.conversationId && isViewing(n.conversationId)) {
        api.notificationsReadConversation(n.conversationId).then(loadNotifications).catch(() => {})
        return
      }
      toast({ title: n.title, body: n.body, imageUrl: n.imageUrl, colorId: n.conversationId, conversationId: n.conversationId })
    },
    reconnect: () => sync().catch(() => {}),
  }

  useEffect(() => {
    const closeChat = connectSocket({
      path: '/ws',
      token,
      subscriptions: {
        '/user/queue/messages': (m) => handlers.current.message(m),
        '/user/queue/receipts': (c) => handlers.current.receipts(c),
        '/user/queue/conversations': (u) => handlers.current.conversation(u),
        '/user/queue/users': (p) => handlers.current.user(p),
      },
      onStatus: (s) => setOnline(s === 'online'),
      onReconnect: () => handlers.current.reconnect(),
      onAuthError: onSignOut,
    })
    const closeNotifications = connectSocket({
      path: '/ws-notifications',
      token,
      subscriptions: { '/user/queue/notifications': (n) => handlers.current.notification(n) },
      onReconnect: () => loadNotifications(),
    })
    return () => { closeChat(); closeNotifications() }
  }, [token, onSignOut, loadNotifications])

  // Coming back to the tab counts as reading the open chat.
  useEffect(() => {
    const onVisible = () => {
      const a = stateRef.current.activeId
      if (document.visibilityState === 'visible' && a && stateRef.current.conversations[a]?.unreadCount) markRead(a)
    }
    document.addEventListener('visibilitychange', onVisible)
    return () => document.removeEventListener('visibilitychange', onVisible)
  }, [markRead])

  // ---------- derived ----------
  const { chats, requests } = useMemo(() => sortedConversations(state), [state])
  const active = state.activeId ? state.conversations[state.activeId] : null
  const totalUnread = chats.reduce((n, c) => n + (c.unreadCount || 0), 0)

  useEffect(() => {
    const n = totalUnread + requests.length
    document.title = n ? `(${n}) tile chat` : 'tile chat'
  }, [totalUnread, requests.length])

  // ---------- actions from flyouts ----------
  const openDirect = async (userId) => {
    const c = await api.openDirect(userId)
    dispatch({ type: 'conversationUpserted', conversation: c })
    openConversation(c.id)
  }
  const createGroup = async (name, memberIds, idempotencyKey) => {
    const c = await withRetry(() => api.createGroup(name, memberIds, idempotencyKey))
    dispatch({ type: 'conversationUpserted', conversation: c })
    openConversation(c.id)
  }
  const addMembers = async (cid, ids) => {
    const c = await api.addMembers(cid, ids)
    dispatch({ type: 'conversationUpserted', conversation: c })
  }
  const deleteChat = async (cid) => {
    try {
      await api.deleteConversation(cid)
      dispatch({ type: 'conversationRemoved', id: cid })
      setPanel(null)
    } catch (e) { showError(e) }
  }
  const leave = async (cid) => {
    await api.leave(cid)
    dispatch({ type: 'conversationRemoved', id: cid })
    setPanel(null)
  }
  const acceptRequest = async (cid) => {
    try {
      const c = await api.accept(cid)
      dispatch({ type: 'conversationUpserted', conversation: c })
      setTab('chats')
      openConversation(cid)
    } catch (e) { showError(e) }
  }
  const rejectRequest = async (cid) => {
    try {
      await api.reject(cid)
      dispatch({ type: 'conversationRemoved', id: cid })
    } catch (e) { showError(e) }
  }
  const blockUser = async (user, { rejectConversationId } = {}) => {
    try {
      await api.block(user.id)
      dispatch({ type: 'blocked', user })
      if (rejectConversationId) dispatch({ type: 'conversationRemoved', id: rejectConversationId })
      toast({ title: `${user.displayName} is blocked`, body: "They won't be told. Unblock any time in your profile.", colorId: user.id })
    } catch (e) { showError(e) }
  }
  const unblockUser = async (userId) => {
    try {
      await api.unblock(userId)
      dispatch({ type: 'unblocked', id: userId })
    } catch (e) { showError(e) }
  }
  const profileSaved = (u) => {
    dispatch({ type: 'me', me: u })
    dispatch({ type: 'profileUpdated', profile: u })
    onUserChange(u)
  }
  const readAllNotifications = async () => {
    await api.notificationsReadAll().catch(() => {})
    loadNotifications()
  }

  const togglePanel = (p) => setPanel((cur) => (cur === p ? null : p))

  return (
    <div className={`shell ${active ? 'has-active' : ''}`}>
      <nav className="rail" aria-label="Main">
        <button className={`icon-btn ${!panel ? 'active' : ''}`} title="Chats" aria-label="Chats" onClick={() => { setPanel(null); dispatch({ type: 'setActive', id: null }) }}>
          <ChatIcon />
          {totalUnread > 0 && <span className="badge">{totalUnread > 99 ? '99+' : totalUnread}</span>}
        </button>
        <button className={`icon-btn ${panel === 'direct' ? 'active' : ''}`} title="New chat" aria-label="New chat" onClick={() => togglePanel('direct')}><PlusPersonIcon /></button>
        <button className={`icon-btn ${panel === 'group' ? 'active' : ''}`} title="New group" aria-label="New group" onClick={() => togglePanel('group')}><GroupIcon /></button>
        <button className={`icon-btn ${panel === 'notifications' ? 'active' : ''}`} title="Notifications" aria-label="Notifications" onClick={() => togglePanel('notifications')}>
          <BellIcon />
          {notifs.unreadCount > 0 && <span className="badge">{notifs.unreadCount > 99 ? '99+' : notifs.unreadCount}</span>}
        </button>
        <div className="rail-spacer" />
        <button className="icon-btn" title="Switch theme" aria-label="Switch theme"
                onClick={() => setPrefs((p) => ({ ...p, theme: p.theme === 'dark' ? 'light' : 'dark' }))}>
          {prefs.theme === 'dark' ? <SunIcon /> : <MoonIcon />}
        </button>
        <span className={`conn-dot ${online ? '' : 'offline'}`} title={online ? 'Connected' : 'Reconnecting…'} />
        <button className="rail-me" title="Your profile" aria-label="Your profile" onClick={() => togglePanel('profile')}>
          <Avatar name={me.displayName} url={me.avatarUrl} colorId={me.id} size={44} />
        </button>
      </nav>

      <ConversationList
        chats={chats}
        requests={requests}
        tab={tab}
        onTab={setTab}
        activeId={state.activeId}
        meId={me.id}
        loading={loading}
        onOpen={openConversation}
        onNewChat={() => setPanel('direct')}
      />

      {active ? (
        <ChatView
          key={active.id}
          conversation={active}
          messages={state.messages[active.id]}
          hasMore={state.hasMore[active.id]}
          me={me}
          blocked={state.blocked}
          onAccept={() => acceptRequest(active.id)}
          onReject={() => rejectRequest(active.id)}
          onBlock={(user, opts) => blockUser(user, opts)}
          onUnblock={unblockUser}
          onDelete={() => deleteChat(active.id)}
          onSendText={(text) => sendText(active.id, text)}
          onSendImage={(file, caption) => sendImage(active.id, file, caption)}
          onRetry={retry}
          onLoadOlder={() => loadOlder(active.id)}
          onBack={() => dispatch({ type: 'setActive', id: null })}
          onInfo={() => togglePanel('info')}
          onOpenImage={setLightbox}
          onError={showError}
        />
      ) : (
        <EmptyChat onNewChat={() => setPanel('direct')} />
      )}

      {panel === 'direct' && <NewChatFlyout mode="direct" meId={me.id} onClose={() => setPanel(null)} onOpenDirect={openDirect} />}
      {panel === 'group' && <NewChatFlyout mode="group" meId={me.id} onClose={() => setPanel(null)} onCreateGroup={createGroup} />}
      {panel === 'notifications' && (
        <NotificationsFlyout notifs={notifs} onClose={() => setPanel(null)} onReadAll={readAllNotifications}
                             onOpen={(n) => n.conversationId && state.conversations[n.conversationId] && openConversation(n.conversationId)} />
      )}
      {panel === 'profile' && (
        <ProfileFlyout me={me} blocked={Object.values(state.blocked)} onUnblock={unblockUser} prefs={prefs} setPrefs={setPrefs} onClose={() => setPanel(null)} onSaved={profileSaved} onSignOut={onSignOut} />
      )}
      {panel === 'info' && active && (
        <GroupInfoFlyout conversation={active} me={me} onClose={() => setPanel(null)}
                         onAdd={(ids) => addMembers(active.id, ids)} onLeave={() => deleteChat(active.id)} />
      )}

      <Toasts toasts={toasts} onOpen={(t) => {
        setToasts((ts) => ts.filter((x) => x.id !== t.id))
        if (t.conversationId && state.conversations[t.conversationId]) openConversation(t.conversationId)
      }} />
      {lightbox && <Lightbox src={lightbox} onClose={() => setLightbox(null)} />}
    </div>
  )
}
