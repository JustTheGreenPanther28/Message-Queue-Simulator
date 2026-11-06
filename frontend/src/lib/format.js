export const ACCENTS = [
  { name: 'cobalt', value: '#0050EF' },
  { name: 'teal', value: '#00ABA9' },
  { name: 'emerald', value: '#008A00' },
  { name: 'crimson', value: '#A20025' },
  { name: 'violet', value: '#6A00FF' },
  { name: 'orange', value: '#FA6800' },
  { name: 'magenta', value: '#D80073' },
  { name: 'steel', value: '#647687' },
]

const TILE_COLORS = ['#0050EF', '#00ABA9', '#008A00', '#A20025', '#6A00FF', '#FA6800', '#D80073', '#1BA1E2', '#60A917', '#AA00FF']

export const tileColor = (id) => TILE_COLORS[Math.abs(Number(id) || 0) % TILE_COLORS.length]

export function initials(name = '') {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) return '?'
  return (parts[0][0] + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase()
}

export const timeOf = (iso) =>
  iso ? new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : ''

export function shortWhen(iso) {
  if (!iso) return ''
  const d = new Date(iso)
  const now = new Date()
  if (d.toDateString() === now.toDateString()) return timeOf(iso)
  const diffDays = (now - d) / 86400000
  if (diffDays < 7) return d.toLocaleDateString([], { weekday: 'short' })
  return d.toLocaleDateString([], { day: 'numeric', month: 'short' })
}

/** Title, picture and colour id for a conversation, from the viewer's point of view. */
export function conversationLook(conv, meId) {
  if (!conv) return { title: '', avatarUrl: null, colorId: 0 }
  if (conv.type === 'GROUP') {
    return { title: conv.name, avatarUrl: null, colorId: conv.id, isGroup: true }
  }
  const other = conv.members.find((m) => m.id !== meId) || conv.members[0]
  return { title: other?.displayName ?? 'Chat', avatarUrl: other?.avatarUrl, colorId: other?.id ?? conv.id, other }
}

export function messagePreview(msg, meId) {
  if (!msg) return 'No messages yet'
  const prefix = msg.senderId === meId ? 'You: ' : ''
  if (msg.type === 'IMAGE') return prefix + (msg.content ? `📷 ${msg.content}` : '📷 Photo')
  return prefix + msg.content
}

export const newId = () =>
  globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
