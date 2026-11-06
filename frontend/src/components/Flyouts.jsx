import { useEffect, useRef, useState } from 'react'
import { api, uploadFile } from '../lib/api.js'
import { ACCENTS, newId, shortWhen } from '../lib/format.js'
import Avatar from './Avatar.jsx'
import { BackIcon, CheckIcon, SignOutIcon } from './Icons.jsx'

/** Slide-in panel from the right edge, like the Windows 8 charms / settings panes. */
function Flyout({ title, onClose, children }) {
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])
  return (
    <>
      <div className="scrim" onClick={onClose} />
      <aside className="flyout" role="dialog" aria-label={title}>
        <div className="flyout-head">
          <button className="icon-btn" onClick={onClose} aria-label="Close"><BackIcon /></button>
          <h2>{title}</h2>
        </div>
        <div className="flyout-body">{children}</div>
      </aside>
    </>
  )
}

/** Debounced user search. `selected` (a Set) turns it into a multi-select. */
function UserPicker({ excludeIds = [], selected, onPick, autoFocus = true }) {
  const [q, setQ] = useState('')
  const [results, setResults] = useState([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    const term = q.trim()
    if (!term) { setResults([]); return }
    let cancelled = false
    setBusy(true)
    const t = setTimeout(() => {
      api.searchUsers(term)
        .then((r) => { if (!cancelled) { setResults(r); setError('') } })
        .catch((e) => !cancelled && setError(e.message))
        .finally(() => !cancelled && setBusy(false))
    }, 250)
    return () => { cancelled = true; clearTimeout(t) }
  }, [q])

  const shown = results.filter((u) => !excludeIds.includes(u.id))
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <input className="input" placeholder="Search by name or username" value={q} autoFocus={autoFocus}
             onChange={(e) => setQ(e.target.value)} aria-label="Search people" />
      <div className="error-text">{error}</div>
      {busy && <span className="spinner muted"><i /><i /><i /></span>}
      {!busy && q.trim() && shown.length === 0 && <span className="muted small">No one found.</span>}
      {shown.map((u, i) => (
        <button key={u.id} className="person" style={{ animationDelay: `${i * 25}ms` }} onClick={() => onPick(u)}>
          <Avatar name={u.displayName} url={u.avatarUrl} colorId={u.id} size={40} />
          <span className="grow">
            <div className="name">{u.displayName}</div>
            <div className="small muted">@{u.username}</div>
          </span>
          {selected && <span className={`check ${selected.has(u.id) ? 'on' : ''}`}>{selected.has(u.id) && <CheckIcon />}</span>}
        </button>
      ))}
    </div>
  )
}

export function NewChatFlyout({ mode, meId, onClose, onOpenDirect, onCreateGroup }) {
  const [picked, setPicked] = useState(new Map())
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  // One key per open form: double-clicking "Create" or a retry can't make two groups.
  const idempotencyKey = useRef(newId())

  const run = async (fn) => {
    setBusy(true)
    setError('')
    try { await fn() } catch (e) { setError(e.message) } finally { setBusy(false) }
  }

  if (mode === 'direct') {
    return (
      <Flyout title="new chat" onClose={onClose}>
        <p className="muted" style={{ margin: 0 }}>Find someone to talk to.</p>
        <div className="error-text">{error}</div>
        <UserPicker excludeIds={[meId]} onPick={(u) => !busy && run(() => onOpenDirect(u.id))} />
      </Flyout>
    )
  }

  const toggle = (u) => setPicked((p) => {
    const next = new Map(p)
    next.has(u.id) ? next.delete(u.id) : next.set(u.id, u)
    return next
  })

  return (
    <Flyout title="new group" onClose={onClose}>
      <div className="field">
        <label htmlFor="groupName">Group name</label>
        <input id="groupName" className="input" value={name} maxLength={64} autoFocus onChange={(e) => setName(e.target.value)} />
      </div>
      {picked.size > 0 && (
        <div className="chips">
          {[...picked.values()].map((u) => (
            <span className="chip" key={u.id}>{u.displayName}<button onClick={() => toggle(u)} aria-label={`Remove ${u.displayName}`}>×</button></span>
          ))}
        </div>
      )}
      <p className="section-title">Members</p>
      <UserPicker excludeIds={[meId]} selected={new Set(picked.keys())} onPick={toggle} autoFocus={false} />
      <div className="error-text">{error}</div>
      <button className="btn btn-accent" disabled={busy || !name.trim() || picked.size === 0}
              onClick={() => run(() => onCreateGroup(name.trim(), [...picked.keys()], idempotencyKey.current))}>
        {busy ? <span className="spinner"><i /><i /><i /></span> : `Create group (${picked.size + 1})`}
      </button>
    </Flyout>
  )
}

export function GroupInfoFlyout({ conversation, me, onClose, onAdd, onLeave }) {
  const [adding, setAdding] = useState(false)
  const [error, setError] = useState('')
  const memberIds = conversation.members.map((m) => m.id)

  const act = async (fn) => {
    setError('')
    try { await fn() } catch (e) { setError(e.message) }
  }

  return (
    <Flyout title="group info" onClose={onClose}>
      <div style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
        <Avatar name={conversation.name} colorId={conversation.id} size={64} group />
        <div>
          <div className="title-m">{conversation.name}</div>
          <div className="muted small">{conversation.members.filter((m) => m.status !== 'PENDING').length} members</div>
        </div>
      </div>
      <p className="section-title">Members</p>
      <div>
        {conversation.members.map((m) => (
          <div key={m.id} className="person" style={{ cursor: 'default' }}>
            <Avatar name={m.displayName} url={m.avatarUrl} colorId={m.id} size={36} />
            <span className="grow">
              <div className="name">{m.id === me.id ? `${m.displayName} (you)` : m.displayName}</div>
              <div className="small muted">@{m.username}{m.id === conversation.createdBy ? ' · created the group' : ''}</div>
            </span>
            {m.status === 'PENDING' && <span className="tag">invited</span>}
          </div>
        ))}
      </div>
      {adding
        ? <><p className="small muted" style={{ margin: 0 }}>People you add get an invite they can accept or decline.</p>
            <UserPicker excludeIds={memberIds} onPick={(u) => act(() => onAdd([u.id]))} /></>
        : <button className="btn btn-ghost" onClick={() => setAdding(true)}>Add people</button>}
      <div className="error-text">{error}</div>
      <button className="btn btn-danger" onClick={() => window.confirm(`Leave “${conversation.name}” and delete the chat?`) && act(onLeave)}>
        Leave and delete chat
      </button>
    </Flyout>
  )
}

export function ProfileFlyout({ me, blocked, onUnblock, prefs, setPrefs, onClose, onSaved, onSignOut }) {
  const [name, setName] = useState(me.displayName)
  const [busy, setBusy] = useState(false)
  const [status, setStatus] = useState({ error: '', ok: '' })
  const fileInput = useRef(null)

  const run = async (fn, ok) => {
    setBusy(true)
    setStatus({ error: '', ok: '' })
    try {
      onSaved(await fn())
      setStatus({ error: '', ok })
    } catch (e) {
      setStatus({ error: e.message, ok: '' })
      if (e.status === 409) api.me().then((u) => { onSaved(u); setName(u.displayName) }).catch(() => {})
    } finally {
      setBusy(false)
    }
  }

  const pick = (e) => {
    const file = e.target.files?.[0]
    e.target.value = ''
    if (!file) return
    if (file.size > 10 * 1024 * 1024) return setStatus({ error: 'Images must be 10 MB or smaller', ok: '' })
    // Straight to object storage with a presigned URL, then attach (validated server-side).
    run(async () => api.setAvatar(await uploadFile(() => api.avatarUploadUrl(file), file)), 'Picture updated')
  }

  return (
    <Flyout title="profile" onClose={onClose}>
      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-end' }}>
        <div className="profile-tile"><Avatar name={me.displayName} url={me.avatarUrl} colorId={me.id} size={140} /></div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <input ref={fileInput} type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden onChange={pick} />
          <button className="btn btn-accent" disabled={busy} onClick={() => fileInput.current.click()}>Change picture</button>
          {me.avatarUrl && <button className="btn btn-ghost" disabled={busy} onClick={() => run(() => api.removeAvatar(), 'Picture removed')}>Remove</button>}
        </div>
      </div>
      <div className="muted small">@{me.username}</div>

      <form className="field" onSubmit={(e) => { e.preventDefault(); run(() => api.updateProfile(name.trim(), me.version), 'Name saved') }}>
        <label htmlFor="displayName">Display name</label>
        <div style={{ display: 'flex', gap: 8 }}>
          <input id="displayName" className="input" value={name} maxLength={40} onChange={(e) => setName(e.target.value)} />
          <button className="btn btn-accent" disabled={busy || !name.trim() || name.trim() === me.displayName}>Save</button>
        </div>
      </form>
      <div className={status.error ? 'error-text' : 'small'} style={{ color: status.ok ? 'var(--accent)' : undefined }}>
        {status.error || status.ok}
      </div>

      <p className="section-title">Theme</p>
      <div className="toggle">
        {['light', 'dark'].map((t) => (
          <button key={t} className={prefs.theme === t ? 'on' : ''} onClick={() => setPrefs((p) => ({ ...p, theme: t }))}>{t}</button>
        ))}
      </div>

      <p className="section-title">Accent colour</p>
      <div className="accent-grid">
        {ACCENTS.map((a) => (
          <button key={a.value} title={a.name} aria-label={a.name} className={prefs.accent === a.value ? 'on' : ''}
                  style={{ background: a.value }} onClick={() => setPrefs((p) => ({ ...p, accent: a.value }))} />
        ))}
      </div>

      <p className="section-title">Blocked people</p>
      {blocked.length === 0
        ? <span className="muted small">You haven't blocked anyone.</span>
        : blocked.map((u) => (
          <div key={u.id} className="person" style={{ cursor: 'default' }}>
            <Avatar name={u.displayName} url={u.avatarUrl} colorId={u.id} size={36} />
            <span className="grow"><div className="name">{u.displayName}</div><div className="small muted">@{u.username}</div></span>
            <button className="btn btn-ghost" onClick={() => onUnblock(u.id)}>Unblock</button>
          </div>
        ))}

      <button className="btn btn-ghost" onClick={onSignOut} style={{ marginTop: 'auto' }}><SignOutIcon /> Sign out</button>
    </Flyout>
  )
}

export function NotificationsFlyout({ notifs, onClose, onOpen, onReadAll }) {
  return (
    <Flyout title="notifications" onClose={onClose}>
      {notifs.unreadCount > 0 && <button className="btn btn-ghost" onClick={onReadAll}>Mark all as read</button>}
      {notifs.items.length === 0 && <p className="muted">You're all caught up.</p>}
      <div>
        {notifs.items.map((n, i) => (
          <button key={n.id} className={`notif ${n.read ? '' : 'unread'}`} style={{ animationDelay: `${Math.min(i, 10) * 25}ms` }} onClick={() => onOpen(n)}>
            <Avatar name={n.title} url={n.imageUrl} colorId={n.conversationId} size={40} group={n.type === 'ADDED_TO_GROUP'} />
            <span className="grow">
              <div className="t">{n.title}</div>
              <div className="b">{n.body}</div>
            </span>
            <span className="small muted">{shortWhen(n.createdAt)}</span>
          </button>
        ))}
      </div>
    </Flyout>
  )
}
