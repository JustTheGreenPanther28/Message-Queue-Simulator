import { useMemo, useState } from 'react'
import { conversationLook, messagePreview, shortWhen } from '../lib/format.js'
import Avatar from './Avatar.jsx'
import { Ticks } from './Icons.jsx'

/** Metro "pivot": two big lowercase headers; the inactive one is dimmed. */
export default function ConversationList({ chats, requests, tab, onTab, activeId, meId, loading, onOpen, onNewChat }) {
  const [filter, setFilter] = useState('')
  const conversations = tab === 'requests' ? requests : chats
  const shown = useMemo(() => {
    const q = filter.trim().toLowerCase()
    if (!q) return conversations
    return conversations.filter((c) => conversationLook(c, meId).title?.toLowerCase().includes(q))
  }, [conversations, filter, meId])

  return (
    <aside className="list-pane">
      <div className="list-head">
        <div className="pivot" role="tablist">
          <button role="tab" aria-selected={tab === 'chats'} className={tab === 'chats' ? 'on' : ''} onClick={() => onTab('chats')}>chats</button>
          <button role="tab" aria-selected={tab === 'requests'} className={tab === 'requests' ? 'on' : ''} onClick={() => onTab('requests')}>
            requests{requests.length > 0 && <span className="pivot-count">{requests.length}</span>}
          </button>
        </div>
        <input className="input" placeholder="Filter conversations" value={filter} onChange={(e) => setFilter(e.target.value)}
               aria-label="Filter conversations" />
      </div>
      <div className="list-scroll">
        {loading && <div className="list-empty"><span className="spinner"><i /><i /><i /></span></div>}
        {!loading && conversations.length === 0 && tab === 'chats' && (
          <div className="list-empty">
            <span>No conversations yet.</span>
            <button className="btn btn-accent" onClick={onNewChat}>Start a chat</button>
          </div>
        )}
        {!loading && conversations.length === 0 && tab === 'requests' && (
          <div className="list-empty">
            <span>No requests.</span>
            <span className="small">When someone new messages you or invites you to a group, it shows up here first.</span>
          </div>
        )}
        {!loading && conversations.length > 0 && shown.length === 0 && <div className="list-empty">Nothing matches “{filter}”.</div>}
        {shown.map((c, i) => {
          const look = conversationLook(c, meId)
          const last = c.lastMessage
          return (
            <button
              key={c.id}
              className={`conv-item ${c.id === activeId ? 'active' : ''} ${c.unreadCount ? 'unread' : ''}`}
              style={{ animationDelay: `${Math.min(i, 12) * 30}ms` }}
              onClick={() => onOpen(c.id)}
            >
              <Avatar name={look.title} url={look.avatarUrl} colorId={look.colorId} size={48} group={look.isGroup} />
              <div className="conv-body">
                <div className="conv-top">
                  <span className="conv-name">{look.title}</span>
                  <span className="conv-when">{shortWhen(last?.createdAt ?? c.lastActivityAt)}</span>
                </div>
                <div className="conv-bottom">
                  <span className="conv-preview">
                    {last && last.senderId === meId && <Ticks status={last.status} />}
                    <span style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
                      {tab === 'requests' && c.type === 'GROUP' ? 'Invited you to the group' : messagePreview(last, meId)}
                    </span>
                  </span>
                  {c.unreadCount > 0 && <span className="conv-unread">{c.unreadCount > 99 ? '99+' : c.unreadCount}</span>}
                </div>
              </div>
            </button>
          )
        })}
      </div>
    </aside>
  )
}
