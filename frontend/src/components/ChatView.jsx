import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { myStatus } from '../lib/chatState.js'
import { conversationLook, timeOf } from '../lib/format.js'
import Avatar from './Avatar.jsx'
import { BackIcon, CloseIcon, ImageIcon, InfoIcon, MoreIcon, SendIcon, Ticks } from './Icons.jsx'

const MAX_IMAGE = 10 * 1024 * 1024

export function EmptyChat({ onNewChat }) {
  const colors = ['#0050EF', '#00ABA9', '#FA6800', '#6A00FF', '#008A00', '#D80073', '#A20025', '#1BA1E2', '#647687']
  return (
    <main className="chat">
      <div className="chat-empty">
        <div>
          <div className="tiles">{colors.map((c, i) => <span key={c} style={{ background: c, animationDelay: `${i * 45}ms` }} />)}</div>
          <h2 className="title-l">pick a conversation</h2>
          <p className="muted">Or start something new.</p>
          <button className="btn btn-accent" onClick={onNewChat}>New chat</button>
        </div>
      </div>
    </main>
  )
}

function MessageRow({ m, mine, first, showSender, sender, onRetry, onOpenImage }) {
  const imageSrc = m.imageUrl || m.localPreview
  return (
    <div className={`row ${mine ? 'mine' : ''} ${first ? 'first' : ''}`}>
      {!mine && (showSender
        ? <Avatar name={sender?.displayName} url={sender?.avatarUrl} colorId={m.senderId} size={32} />
        : <span className="avatar-gap" />)}
      <div className="bubble">
        {!mine && showSender && sender && <div className="sender">{sender.displayName}</div>}
        {m.type === 'IMAGE' && imageSrc && (
          <button className={`bubble-image ${m.status === 'PENDING' ? 'pending' : ''}`} onClick={() => onOpenImage(imageSrc)} aria-label="Open image">
            <img src={imageSrc} alt={m.content || 'Shared image'} loading="lazy" />
          </button>
        )}
        {m.content && <div className="text">{m.content}</div>}
        <div className="meta">
          <span>{timeOf(m.createdAt)}</span>
          {mine && <Ticks status={m.status} />}
        </div>
        {mine && m.status === 'FAILED' && <button className="retry-link" onClick={() => onRetry(m)}>Not sent. Tap to retry</button>}
      </div>
    </div>
  )
}

export default function ChatView({ conversation, messages, hasMore, me, blocked, onAccept, onReject, onBlock, onUnblock, onDelete,
                                   onSendText, onSendImage, onRetry, onLoadOlder, onBack, onInfo, onOpenImage, onError }) {
  const look = conversationLook(conversation, me.id)
  const isGroup = conversation.type === 'GROUP'
  const pending = myStatus(conversation, me.id) === 'PENDING'
  const other = !isGroup ? look.other : null
  const iBlockedThem = !!(other && blocked[other.id])
  const [menuOpen, setMenuOpen] = useState(false)
  const members = Object.fromEntries(conversation.members.map((m) => [m.id, m]))
  const list = messages || []
  const scroller = useRef(null)
  const stickToBottom = useRef(true)
  const prevHeight = useRef(0)
  const loadingOlder = useRef(false)

  const [text, setText] = useState('')
  const [attachment, setAttachment] = useState(null) // { file, url }
  const fileInput = useRef(null)
  const textarea = useRef(null)

  // Keep pinned to the newest message unless the user scrolled up; keep position when older ones load.
  useLayoutEffect(() => {
    const el = scroller.current
    if (!el) return
    if (loadingOlder.current) {
      el.scrollTop = el.scrollHeight - prevHeight.current
      loadingOlder.current = false
    } else if (stickToBottom.current) {
      el.scrollTop = el.scrollHeight
    }
  }, [list])

  useEffect(() => { textarea.current?.focus() }, [])
  useEffect(() => () => attachment && URL.revokeObjectURL(attachment.url), [attachment])

  const onScroll = () => {
    const el = scroller.current
    stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80
  }

  const older = () => {
    prevHeight.current = scroller.current.scrollHeight
    loadingOlder.current = true
    onLoadOlder()
  }

  const pickFile = (e) => {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    if (!file.type.startsWith('image/')) return onError(new Error('Only images can be shared'))
    if (file.size > MAX_IMAGE) return onError(new Error('Images must be 10 MB or smaller'))
    setAttachment({ file, url: URL.createObjectURL(file) })
  }

  const send = () => {
    const body = text.trim()
    if (attachment) {
      onSendImage(attachment.file, body)
      setAttachment(null)
    } else if (body) {
      onSendText(body)
    } else return
    setText('')
    stickToBottom.current = true
    textarea.current?.focus()
  }

  const onKeyDown = (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault()
      send()
    }
  }

  const joined = conversation.members.filter((m) => m.status !== 'PENDING')
  const invited = conversation.members.length - joined.length
  const sub = isGroup
    ? joined.map((m) => (m.id === me.id ? 'You' : m.displayName)).join(', ') + (invited ? ` · ${invited} invited` : '')
    : `@${look.other?.username ?? ''}`

  return (
    <main className="chat">
      <header className="chat-head">
        <button className="icon-btn back-btn" onClick={onBack} aria-label="Back to chats"><BackIcon /></button>
        <Avatar name={look.title} url={look.avatarUrl} colorId={look.colorId} size={44} group={look.isGroup} />
        <div className="chat-head-text">
          <h2 className="title-m">{look.title}</h2>
          <div className="sub">{sub}</div>
        </div>
        {isGroup && !pending && (
          <button className="icon-btn" onClick={onInfo} title="Group info" aria-label="Group info"><InfoIcon /></button>
        )}
        {other && (
          <button className="icon-btn" onClick={() => setMenuOpen((o) => !o)} title="More" aria-label="More options" aria-expanded={menuOpen}>
            <MoreIcon />
          </button>
        )}
        {menuOpen && other && (
          <div className="menu" role="menu" onMouseLeave={() => setMenuOpen(false)}>
            {!pending && (
              <button role="menuitem" className="danger" onClick={() => {
                setMenuOpen(false)
                if (window.confirm(`Delete this chat? Its messages are removed for you only; ${other.displayName} keeps theirs.`)) onDelete()
              }}>Delete chat</button>
            )}
            {iBlockedThem
              ? <button role="menuitem" onClick={() => { setMenuOpen(false); onUnblock(other.id) }}>Unblock {other.displayName}</button>
              : <button role="menuitem" className="danger" onClick={() => {
                  setMenuOpen(false)
                  if (window.confirm(`Block ${other.displayName}? They won't be told, and their messages won't reach you.`)) onBlock(other)
                }}>Block {other.displayName}</button>}
          </div>
        )}
      </header>

      {isGroup && pending ? (
        <div className="invite-card">
          <div>
            <Avatar name={conversation.name} colorId={conversation.id} size={96} group />
            <h2 className="title-l" style={{ marginTop: 18 }}>{conversation.name}</h2>
            <p className="muted">You've been invited to join this group.</p>
            <div className="people">
              {joined.slice(0, 8).map((m) => <Avatar key={m.id} name={m.displayName} url={m.avatarUrl} colorId={m.id} size={36} />)}
            </div>
            <p className="small muted">{joined.map((m) => m.displayName).join(', ')}</p>
            <div style={{ display: 'flex', gap: 8, justifyContent: 'center', marginTop: 16 }}>
              <button className="btn btn-accent" onClick={onAccept}>Join group</button>
              <button className="btn btn-ghost" onClick={onReject}>Decline</button>
            </div>
          </div>
        </div>
      ) : (<>

      <div className="messages" ref={scroller} onScroll={onScroll}>
        {hasMore && <button className="btn btn-ghost load-earlier" onClick={older}>Load earlier messages</button>}
        {messages === undefined && <div className="muted" style={{ alignSelf: 'center' }}><span className="spinner"><i /><i /><i /></span></div>}
        {messages && list.length === 0 && <div className="muted" style={{ alignSelf: 'center', marginTop: 40 }}>Say hello 👋</div>}
        {list.map((m, i) => {
          const prev = list[i - 1]
          const first = !prev || prev.senderId !== m.senderId
          return (
            <MessageRow key={m.clientMessageId ? `${m.senderId}:${m.clientMessageId}` : m.id} m={m} mine={m.senderId === me.id}
                        first={first} showSender={first && conversation.type === 'GROUP'} sender={members[m.senderId]}
                        onRetry={onRetry} onOpenImage={onOpenImage} />
          )
        })}
      </div>

      {pending ? (
        <div className="request-bar">
          <p><strong>{look.title}</strong> wants to message you.</p>
          <p className="small muted">They won't see that you've read their messages until you accept.</p>
          <div className="actions">
            <button className="btn btn-accent" onClick={onAccept}>Accept</button>
            <button className="btn btn-ghost" onClick={onReject}>Delete</button>
            <button className="btn btn-danger" onClick={() => onBlock(other, { rejectConversationId: conversation.id })}>Block</button>
          </div>
        </div>
      ) : iBlockedThem ? (
        <div className="request-bar">
          <p>You blocked <strong>{other.displayName}</strong>. Their new messages won't reach you.</p>
          <div className="actions"><button className="btn btn-ghost" onClick={() => onUnblock(other.id)}>Unblock</button></div>
        </div>
      ) : (
      <footer className="composer">
        {attachment && (
          <div className="attach-preview">
            <img src={attachment.url} alt="" />
            <span className="small muted" style={{ flex: 1 }}>{attachment.file.name}<br />Add a caption below, or just send.</span>
            <button className="icon-btn" onClick={() => setAttachment(null)} aria-label="Remove image"><CloseIcon /></button>
          </div>
        )}
        <div className="composer-row">
          <input ref={fileInput} type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden onChange={pickFile} />
          <button className="icon-btn" onClick={() => fileInput.current.click()} title="Share an image" aria-label="Share an image"><ImageIcon /></button>
          <textarea ref={textarea} rows={1} value={text} maxLength={4000} placeholder={attachment ? 'Add a caption' : 'Type a message'}
                    onChange={(e) => setText(e.target.value)} onKeyDown={onKeyDown} aria-label="Message" />
          <button className="send-btn" onClick={send} disabled={!text.trim() && !attachment} aria-label="Send"><SendIcon /></button>
        </div>
      </footer>
      )}
      </>)}
    </main>
  )
}
