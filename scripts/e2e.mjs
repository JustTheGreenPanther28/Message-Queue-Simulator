#!/usr/bin/env node
// End-to-end check of the running stack, through the API gateway, like a real client.
//   node scripts/e2e.mjs        (default http://localhost:3000, the web container: nginx → gateway, and /storage)
// Needs Node 22+ (built-in fetch and WebSocket). Exits non-zero if any check fails.

const BASE = process.env.BASE_URL || 'http://localhost:3000'
const WS_BASE = BASE.replace(/^http/, 'ws')
const run = Math.random().toString(36).slice(2, 7)
const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
const uuid = () => crypto.randomUUID()

let passed = 0
let failed = 0
const ok = (name) => { passed++; console.log(`  \x1b[32m✓\x1b[0m ${name}`) }
const bad = (name, detail) => { failed++; console.log(`  \x1b[31m✗ ${name}\x1b[0m\n      ${detail}`) }
function check(name, cond, detail = '') { cond ? ok(name) : bad(name, detail) }
const section = (t) => console.log(`\n\x1b[1m${t}\x1b[0m`)

/** Like a real client: on 429 (rate limited) wait and retry, unless the test wants to see the 429. */
async function http(method, path, opts = {}) {
  for (let attempt = 0; ; attempt++) {
    const res = await httpOnce(method, path, opts)
    if (res.status !== 429 || opts.noRetry || attempt >= 8) return res
    await sleep(1000 + attempt * 500)
  }
}

async function httpOnce(method, path, { token, body, form, headers = {} } = {}) {
  const h = { ...headers }
  if (token) h.Authorization = `Bearer ${token}`
  let payload
  if (form) payload = form
  else if (body !== undefined) { h['Content-Type'] = 'application/json'; payload = JSON.stringify(body) }
  const res = await fetch(BASE + path, { method, headers: h, body: payload })
  const text = await res.text()
  let data = null
  try { data = text ? JSON.parse(text) : null } catch { data = text }
  return { status: res.status, data, headers: res.headers }
}

async function eventually(fn, { timeout = 10000, every = 250 } = {}) {
  const end = Date.now() + timeout
  let last
  while (Date.now() < end) {
    last = await fn()
    if (last) return last
    await sleep(every)
  }
  return last
}

// --- a tiny STOMP 1.2 client, enough to subscribe to /user/queue/* ---
function stomp(path, token) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(WS_BASE + path)
    const timer = setTimeout(() => { ws.close(); reject(new Error(`STOMP connect timeout on ${path}`)) }, 8000)
    const inboxes = {}
    let buffer = ''
    let nextId = 0
    const client = {
      subscribe(destination) {
        const id = `s${nextId++}`
        const inbox = { items: [], waiters: [] }
        inboxes[id] = inbox
        ws.send(`SUBSCRIBE\nid:${id}\ndestination:${destination}\n\n\0`)
        return {
          items: inbox.items,
          async waitFor(pred, timeout = 8000) {
            const found = inbox.items.find(pred)
            if (found) return found
            return new Promise((res) => {
              const timer = setTimeout(() => res(null), timeout)
              inbox.waiters.push({ pred, res: (v) => { clearTimeout(timer); res(v) } })
            })
          },
        }
      },
      close: () => ws.close(),
    }
    ws.onopen = () => ws.send(`CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer ${token}\n\n\0`)
    ws.onerror = () => { clearTimeout(timer); reject(new Error(`WebSocket error on ${path}`)) }
    ws.onmessage = (ev) => {
      buffer += typeof ev.data === 'string' ? ev.data : Buffer.from(ev.data).toString()
      let i
      while ((i = buffer.indexOf('\0')) !== -1) {
        const raw = buffer.slice(0, i).replace(/^\n+/, '')
        buffer = buffer.slice(i + 1)
        if (!raw) continue
        const [head, ...rest] = raw.split('\n\n')
        const [command, ...headerLines] = head.split('\n')
        const headers = Object.fromEntries(headerLines.map((l) => [l.slice(0, l.indexOf(':')), l.slice(l.indexOf(':') + 1)]))
        if (command === 'CONNECTED') { clearTimeout(timer); resolve(client) }
        else if (command === 'ERROR') { clearTimeout(timer); reject(new Error(headers.message || 'STOMP error')) }
        else if (command === 'MESSAGE') {
          const inbox = inboxes[headers.subscription]
          if (!inbox) continue
          const payload = JSON.parse(rest.join('\n\n'))
          inbox.items.push(payload)
          inbox.waiters = inbox.waiters.filter((w) => (w.pred(payload) ? (w.res(payload), false) : true))
        }
      }
    }
  })
}

/** Storage URLs are same-origin paths (proxy mode) or absolute (direct mode). */
const abs = (url) => (url && url.startsWith('/') ? BASE + url : url)
const pathOf = (url) => (url ? new URL(abs(url)).pathname : null)

/** The browser flow: ask for a presigned URL, PUT the bytes straight to object storage, return the key. */
async function upload(ticketPath, token, bytes = PNG, contentType = 'image/png') {
  const t = await http('POST', ticketPath, { token, body: { contentType, size: bytes.length } })
  if (t.status !== 200) return { status: t.status, data: t.data }
  const put = await fetch(abs(t.data.uploadUrl), { method: t.data.method, headers: t.data.headers, body: bytes })
  return { status: put.status, key: t.data.key, ticket: t.data }
}

// 1x1 transparent PNG
const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==', 'base64')

// Never hang a CI job: give up after 4 minutes.
setTimeout(() => { console.error('\nE2E timed out'); process.exit(2) }, 240_000).unref()

async function main() {
  console.log(`tile chat e2e against ${BASE} (run ${run})`)

  section('Gateway & auth (user-service)')
  const routed = await http('GET', '/api/conversations')
  check('API is reachable and routed to chat-service (401 without a token)', routed.status === 401, `${routed.status}`)

  const reg = async (name) => http('POST', '/api/auth/register', { body: { username: `${name}_${run}`, displayName: name[0].toUpperCase() + name.slice(1), password: 'secret123' } })
  const [ra, rb, rc] = await Promise.all([reg('alice'), reg('bob'), reg('carol')])
  check('three users register', [ra, rb, rc].every((r) => r.status === 201), JSON.stringify([ra.data, rb.data, rc.data]))
  const alice = { token: ra.data.token, ...ra.data.user }
  const bob = { token: rb.data.token, ...rb.data.user }
  const carol = { token: rc.data.token, ...rc.data.user }

  const dup = await http('POST', '/api/auth/register', { body: { username: `ALICE_${run}`, displayName: 'x', password: 'secret123' } })
  check('duplicate username (case-insensitive) is rejected with 409', dup.status === 409, `${dup.status}`)
  const badLogin = await http('POST', '/api/auth/login', { body: { username: alice.username, password: 'wrong' } })
  check('wrong password gives 401', badLogin.status === 401, `${badLogin.status}`)
  const login = await http('POST', '/api/auth/login', { body: { username: alice.username, password: 'secret123' } })
  check('login returns a token', login.status === 200 && !!login.data.token)
  const anon = await http('GET', '/api/conversations')
  check('API without a token gives 401', anon.status === 401, `${anon.status}`)

  section('Event propagation (user-service → RabbitMQ → chat-service)')
  let direct
  const t0 = Date.now()
  const opened = await eventually(async () => {
    const r = await http('POST', '/api/conversations/direct', { token: alice.token, body: { userId: bob.id } })
    if (r.status === 200) { direct = r.data; return true }
    return false
  })
  check(`new users reach chat-service via events (${Date.now() - t0} ms)`, opened && direct?.members?.length === 2, JSON.stringify(direct))

  section('Real-time connections')
  const aliceWs = await stomp('/ws', alice.token)
  const bobWs = await stomp('/ws', bob.token)
  const bobNotifWs = await stomp('/ws-notifications', bob.token)
  const aliceReceipts = aliceWs.subscribe('/user/queue/receipts')
  const aliceUsers = aliceWs.subscribe('/user/queue/users')
  const bobMessages = bobWs.subscribe('/user/queue/messages')
  const bobConversations = bobWs.subscribe('/user/queue/conversations')
  const bobNotifications = bobNotifWs.subscribe('/user/queue/notifications')
  await sleep(300)
  ok('STOMP connected to chat-service and notification-service through the gateway')
  const rejected = await stomp('/ws', 'not-a-token').then(() => false, () => true)
  check('WebSocket with an invalid token is refused', rejected)

  section('Idempotent sending')
  const cmid = uuid()
  const sends = await Promise.all(Array.from({ length: 6 }, () =>
    http('POST', `/api/conversations/${direct.id}/messages`, { token: alice.token, body: { clientMessageId: cmid, content: 'hello bob' } })))
  const ids = new Set(sends.map((s) => s.data?.id))
  check('6 concurrent retries with the same clientMessageId → 1 message', sends.every((s) => s.status === 200) && ids.size === 1, JSON.stringify(sends.map((s) => s.status)))
  const first = sends[0].data
  check('new message starts as SENT', first.status === 'SENT', first.status)
  const pushed = await bobMessages.waitFor((m) => m.id === first.id)
  check('bob receives it live over WebSocket', !!pushed)
  const notif = await bobNotifications.waitFor((n) => n.body === 'hello bob')
  check('bob gets a notification (chat-service → outbox → RabbitMQ → notification-service)', !!notif, 'no notification within 8s')
  await sleep(500)
  check('…exactly once', bobMessages.items.filter((m) => m.id === first.id).length === 1 &&
        bobNotifications.items.filter((n) => n.body === 'hello bob').length === 1)

  section('Message requests')
  const bobList = (await http('GET', '/api/conversations', { token: bob.token })).data
  const asBob = bobList.find((c) => c.id === direct.id)
  check('for bob the new chat is a request (his status PENDING)', asBob?.members.find((m) => m.id === bob.id)?.status === 'PENDING')
  check('…and the notification says so', notif?.type === 'MESSAGE_REQUEST' && /wants to message you/.test(notif?.title), JSON.stringify(notif))
  const early = await http('POST', `/api/conversations/${direct.id}/messages`, { token: bob.token, body: { clientMessageId: uuid(), content: 'hi?' } })
  check('bob cannot reply before accepting (403)', early.status === 403, `${early.status}`)
  await http('POST', '/api/messages/delivered', { token: bob.token, body: { messageIds: [first.id] } })
  await http('POST', `/api/conversations/${direct.id}/read`, { token: bob.token })
  await sleep(400)
  const stillSent = (await http('GET', `/api/conversations/${direct.id}/messages`, { token: alice.token })).data.find((m) => m.id === first.id)
  check('previewing a request sends no receipts (alice still sees SENT)', stillSent?.status === 'SENT', stillSent?.status)

  section('Delivery and read receipts')
  const acc = await http('POST', `/api/conversations/${direct.id}/accept`, { token: bob.token })
  const acc2 = await http('POST', `/api/conversations/${direct.id}/accept`, { token: bob.token })
  check('bob accepts (and accepting again is harmless)', acc.status === 200 && acc2.status === 200)
  const delivered = await aliceReceipts.waitFor((rs) => rs.some((r) => r.messageId === first.id && r.status === 'DELIVERED'))
  check('accepting confirms delivery → alice gets DELIVERED live', !!delivered)
  const again = await http('POST', '/api/conversations/direct', { token: bob.token, body: { userId: alice.id } })
  check('opening the same direct chat from the other side returns the same conversation', again.data?.id === direct.id)
  await http('POST', '/api/messages/delivered', { token: bob.token, body: { messageIds: [first.id] } })
  ok('repeating the delivery ack is harmless')
  await http('POST', `/api/conversations/${direct.id}/read`, { token: bob.token })
  const read = await aliceReceipts.waitFor((rs) => rs.some((r) => r.messageId === first.id && r.status === 'READ'))
  check('bob opens the chat → alice gets READ live', !!read)
  const hist = await http('GET', `/api/conversations/${direct.id}/messages`, { token: alice.token })
  check('history shows status READ', hist.data?.find((m) => m.id === first.id)?.status === 'READ')

  section('Ordering under concurrency')
  await Promise.all(Array.from({ length: 20 }, (_, i) =>
    http('POST', `/api/conversations/${direct.id}/messages`, { token: i % 2 ? alice.token : bob.token, body: { clientMessageId: uuid(), content: `burst ${i}` } })))
  const burst = await http('GET', `/api/conversations/${direct.id}/messages?limit=100`, { token: alice.token })
  const seqs = burst.data.map((m) => m.seq)
  check('21 messages with seq 1..21, no gaps or duplicates', JSON.stringify(seqs) === JSON.stringify(Array.from({ length: 21 }, (_, i) => i + 1)), JSON.stringify(seqs))
  const catchUp = await http('GET', `/api/conversations/${direct.id}/messages?afterSeq=18`, { token: bob.token })
  check('catch-up after reconnect (afterSeq=18) returns exactly 19..21', JSON.stringify(catchUp.data.map((m) => m.seq)) === '[19,20,21]')

  section('Group chat')
  const key = uuid()
  const g1 = await http('POST', '/api/conversations/group', { token: alice.token, headers: { 'Idempotency-Key': key }, body: { name: 'Team', memberIds: [bob.id, carol.id] } })
  const g2 = await http('POST', '/api/conversations/group', { token: alice.token, headers: { 'Idempotency-Key': key }, body: { name: 'Team', memberIds: [bob.id, carol.id] } })
  check('group created; retry with the same Idempotency-Key returns the same group', g1.status === 201 && g2.data?.id === g1.data.id, `${g1.status} ${g2.data?.id} ${g1.data?.id}`)
  const group = g1.data
  const added = await bobNotifications.waitFor((n) => n.type === 'ADDED_TO_GROUP' && n.conversationId === group.id)
  check('bob gets an invite notification', !!added && /invited you/.test(added.body), JSON.stringify(added))
  const peekInvite = await http('GET', `/api/conversations/${group.id}/messages`, { token: carol.token })
  check('an invite shows no messages until accepted (403)', peekInvite.status === 403, `${peekInvite.status}`)
  await http('POST', `/api/conversations/${group.id}/accept`, { token: bob.token })
  await http('POST', `/api/conversations/${group.id}/accept`, { token: carol.token })
  ok('bob and carol accept the invite')
  const gm = (await http('POST', `/api/conversations/${group.id}/messages`, { token: alice.token, body: { clientMessageId: uuid(), content: 'hi team' } })).data
  const status = async () => (await http('GET', `/api/conversations/${group.id}/messages`, { token: alice.token })).data.find((m) => m.id === gm.id).status
  await http('POST', `/api/conversations/${group.id}/read`, { token: bob.token })
  check('one of two recipients read it → still SENT (carol has not received it)', (await status()) === 'SENT')
  await http('POST', '/api/messages/delivered', { token: carol.token, body: {} })
  check('carol syncs (delivered-all) → DELIVERED', (await status()) === 'DELIVERED')
  await http('POST', `/api/conversations/${group.id}/read`, { token: carol.token })
  check('everyone read it → READ', (await status()) === 'READ')
  const groupNotif = await bobNotifications.waitFor((n) => n.body === 'Alice: hi team')
  check('group notification reads "Alice: hi team" titled "Team"', groupNotif?.title === 'Team')

  section('Authorisation')
  const peek = await http('GET', `/api/conversations/${direct.id}/messages`, { token: carol.token })
  check('carol cannot read the alice↔bob chat (404)', peek.status === 404, `${peek.status}`)
  const inject = await http('POST', `/api/conversations/${direct.id}/messages`, { token: carol.token, body: { clientMessageId: uuid(), content: 'x' } })
  check('carol cannot post into it either', inject.status === 404, `${inject.status}`)

  section('Images (presigned object storage)')
  const up = await upload(`/api/conversations/${direct.id}/attachments/upload-url`, alice.token)
  check('browser-style upload: PUT straight to object storage with a presigned URL', up.status === 200 && up.key?.startsWith(`tmp/${alice.id}/`), JSON.stringify(up))
  const imgCmid = uuid()
  const img = await http('POST', `/api/conversations/${direct.id}/messages`, { token: alice.token, body: { clientMessageId: imgCmid, content: 'look', uploadKey: up.key } })
  check('image message is sent with the upload key', img.status === 200 && img.data.type === 'IMAGE' && img.data.imageUrl?.startsWith('/storage/') && img.data.imageUrl.includes('X-Amz-Signature'), JSON.stringify(img.data))
  const imgRetry = await http('POST', `/api/conversations/${direct.id}/messages`, { token: alice.token, body: { clientMessageId: imgCmid, content: 'look', uploadKey: up.key } })
  check('retrying the image send returns the same message', imgRetry.data?.id === img.data.id)
  const file = img.data?.imageUrl ? await fetch(abs(img.data.imageUrl)) : { status: 0, headers: new Headers() }
  check('image is readable same-origin through its presigned URL as image/png', file.status === 200 && file.headers.get('content-type') === 'image/png', `${file.status} ${file.headers.get('content-type')}`)
  const bobsCopy = (await http('GET', `/api/conversations/${direct.id}/messages`, { token: bob.token })).data.find((m) => m.id === img.data.id)
  check('bob gets his own signed URL for it', !!bobsCopy?.imageUrl && (await fetch(abs(bobsCopy.imageUrl))).status === 200)
  const unsigned = img.data?.imageUrl ? await fetch(abs(img.data.imageUrl.split('?')[0])) : { status: 0 }
  check('the bucket is private: the same URL without a signature is refused', unsigned.status === 403, `${unsigned.status}`)
  const tampered = img.data?.imageUrl ? await fetch(abs(img.data.imageUrl.replace('.png?', 'x.png?'))) : { status: 0 }
  check('a signature only works for its own object', tampered.status === 403, `${tampered.status}`)
  const fakeUp = await upload(`/api/conversations/${direct.id}/attachments/upload-url`, alice.token, Buffer.from('<script>alert(1)</script>'), 'image/png')
  const fake = await http('POST', `/api/conversations/${direct.id}/messages`, { token: alice.token, body: { clientMessageId: uuid(), uploadKey: fakeUp.key } })
  check('a non-image uploaded as image/png is rejected on attach (400)', fake.status === 400, `${fake.status} ${JSON.stringify(fake.data)}`)
  const stolen = await http('POST', `/api/conversations/${direct.id}/messages`, { token: bob.token, body: { clientMessageId: uuid(), uploadKey: up.key } })
  check("bob can't attach alice's upload (400)", stolen.status === 400, `${stolen.status}`)
  const ticket = await http('POST', `/api/conversations/${direct.id}/attachments/upload-url`, { token: alice.token, body: { contentType: 'image/png', size: PNG.length } })
  const sizeCheat = await fetch(abs(ticket.data.uploadUrl), { method: 'PUT', headers: ticket.data.headers, body: Buffer.concat([PNG, Buffer.alloc(5000)]) })
  check('uploading more bytes than were signed for is refused', sizeCheat.status === 403 || sizeCheat.status === 400, `${sizeCheat.status}`)
  const tooBig = await http('POST', `/api/conversations/${direct.id}/attachments/upload-url`, { token: alice.token, body: { contentType: 'image/png', size: 11 * 1024 * 1024 } })
  check('files over 10 MB are refused before upload', tooBig.status === 400)
  const carolUpload = await http('POST', `/api/conversations/${direct.id}/attachments/upload-url`, { token: carol.token, body: { contentType: 'image/png', size: 10 } })
  check('outsiders get no upload URL for the chat (404)', carolUpload.status === 404, `${carolUpload.status}`)

  section('Profile & picture')
  const avUp = await upload('/api/users/me/avatar/upload-url', bob.token)
  const av = await http('PUT', '/api/users/me/avatar', { token: bob.token, body: { uploadKey: avUp.key } })
  check('bob sets a profile picture (presigned upload → attach)', av.status === 200 && pathOf(av.data.avatarUrl)?.includes(`/avatars/${bob.id}/`), JSON.stringify(av.data))
  const avFile = av.data?.avatarUrl ? await fetch(abs(av.data.avatarUrl)) : { status: 0 }
  check('picture is readable through its presigned URL', avFile.status === 200)
  const live = await aliceUsers.waitFor((u) => u.id === bob.id && u.avatarUrl && pathOf(u.avatarUrl) === pathOf(av.data?.avatarUrl))
  check('alice sees bob\'s new picture live (user.updated → chat-service → WebSocket)', !!live)
  const me = (await http('GET', '/api/users/me', { token: bob.token })).data
  const rename = await http('PATCH', '/api/users/me', { token: bob.token, body: { displayName: 'Bobby', version: me.version } })
  const stale = await http('PATCH', '/api/users/me', { token: bob.token, body: { displayName: 'Robert', version: me.version } })
  check('rename works; a stale version is rejected with 409', rename.status === 200 && stale.status === 409, `${rename.status} ${stale.status}`)
  const renamed = await eventually(async () => {
    const list = (await http('GET', '/api/conversations', { token: alice.token })).data
    return list.find((c) => c.id === direct.id)?.members.some((m) => m.id === bob.id && m.displayName === 'Bobby')
  })
  check('the rename reaches alice\'s conversation list', !!renamed)

  section('Notifications API')
  const before = (await http('GET', '/api/notifications', { token: bob.token })).data
  check('bob has unread notifications', before.unreadCount > 0, JSON.stringify(before.unreadCount))
  await http('POST', `/api/notifications/read?conversationId=${direct.id}`, { token: bob.token })
  const afterOne = (await http('GET', '/api/notifications', { token: bob.token })).data
  check('opening a chat clears its notifications only', afterOne.unreadCount < before.unreadCount && afterOne.unreadCount > 0)
  await http('POST', '/api/notifications/read-all', { token: bob.token })
  check('mark all read → 0', (await http('GET', '/api/notifications', { token: bob.token })).data.unreadCount === 0)

  section('Silent blocking')
  const rd = await http('POST', '/api/auth/register', { body: { username: `dave_${run}`, displayName: 'Dave', password: 'secret123' } })
  const dave = { token: rd.data.token, ...rd.data.user }
  const daveWs = await stomp('/ws', dave.token)
  const daveMessages = daveWs.subscribe('/user/queue/messages')
  const daveConvs = daveWs.subscribe('/user/queue/conversations')
  const daveNotifWs = await stomp('/ws-notifications', dave.token)
  const daveNotifs = daveNotifWs.subscribe('/user/queue/notifications')
  await sleep(300)
  const b1 = await http('PUT', `/api/blocks/${alice.id}`, { token: dave.token })
  const b2 = await http('PUT', `/api/blocks/${alice.id}`, { token: dave.token })
  check('dave blocks alice (and blocking twice is harmless)', b1.status === 204 && b2.status === 204, `${b1.status} ${b2.status}`)
  const ad = await eventually(async () => {
    const r = await http('POST', '/api/conversations/direct', { token: alice.token, body: { userId: dave.id } })
    return r.status === 200 ? r.data : null
  })
  const ghost = await http('POST', `/api/conversations/${ad.id}/messages`, { token: alice.token, body: { clientMessageId: uuid(), content: 'hey dave' } })
  check('alice can still open a chat and send (she is not told)', ghost.status === 200 && ghost.data.status === 'SENT')
  await sleep(1500)
  check('dave receives nothing: no message, no chat, no notification',
    !daveMessages.items.some((m) => m.id === ghost.data.id) && daveConvs.items.length === 0 && daveNotifs.items.length === 0,
    JSON.stringify({ m: daveMessages.items.length, c: daveConvs.items.length, n: daveNotifs.items.length }))
  const daveList = (await http('GET', '/api/conversations', { token: dave.token })).data
  check('the chat is not in dave\'s list', !daveList.some((c) => c.id === ad.id))
  await http('POST', '/api/messages/delivered', { token: dave.token, body: {} })
  const ghostStatus = (await http('GET', `/api/conversations/${ad.id}/messages`, { token: alice.token })).data.find((m) => m.id === ghost.data.id)?.status
  check('alice sees a single tick forever (SENT)', ghostStatus === 'SENT', ghostStatus)
  const list = (await http('GET', '/api/blocks', { token: dave.token })).data
  check('dave\'s block list shows alice', list.length === 1 && list[0].id === alice.id)
  await http('DELETE', `/api/blocks/${alice.id}`, { token: dave.token })
  const after = await http('POST', `/api/conversations/${ad.id}/messages`, { token: alice.token, body: { clientMessageId: uuid(), content: 'second try' } })
  const daveSees = await daveMessages.waitFor((m) => m.id === after.data.id)
  check('after unblocking, new messages arrive as a request', !!daveSees)
  const daveHistory = (await http('GET', `/api/conversations/${ad.id}/messages`, { token: dave.token })).data.map((m) => m.content)
  check('…but messages sent while blocked stay hidden', JSON.stringify(daveHistory) === '["second try"]', JSON.stringify(daveHistory))
  await http('PUT', `/api/blocks/${alice.id}`, { token: bob.token })
  const blockedReply = await http('POST', `/api/conversations/${direct.id}/messages`, { token: bob.token, body: { clientMessageId: uuid(), content: 'x' } })
  check('you cannot message someone you blocked (403)', blockedReply.status === 403, `${blockedReply.status}`)
  await http('DELETE', `/api/blocks/${alice.id}`, { token: bob.token })
  ;[daveWs, daveNotifWs].forEach((c) => c.close())

  section('Deleting a chat')
  const del1 = await http('DELETE', `/api/conversations/${direct.id}`, { token: bob.token })
  const del2 = await http('DELETE', `/api/conversations/${direct.id}`, { token: bob.token })
  check('bob deletes the chat with alice (deleting twice is harmless)', del1.status === 204 && del2.status === 204, `${del1.status} ${del2.status}`)
  const bobAfterDelete = (await http('GET', '/api/conversations', { token: bob.token })).data
  check('it is gone from bob\'s list', !bobAfterDelete.some((c) => c.id === direct.id))
  const aliceStill = (await http('GET', `/api/conversations/${direct.id}/messages?limit=100`, { token: alice.token })).data
  check('alice still has the full history', aliceStill.length >= 22, `${aliceStill.length}`)
  const comeback = await http('POST', `/api/conversations/${direct.id}/messages`, { token: alice.token, body: { clientMessageId: uuid(), content: 'you there?' } })
  const back = await bobConversations.waitFor((u) => u.action === 'UPSERT' && u.conversationId === direct.id && u.conversation?.lastMessage?.id === comeback.data.id)
  check('a new message from alice brings the chat back to bob, live', !!back)
  const bobFresh = (await http('GET', `/api/conversations/${direct.id}/messages`, { token: bob.token })).data.map((m) => m.content)
  check('…showing only what came after he deleted it', JSON.stringify(bobFresh) === '["you there?"]', JSON.stringify(bobFresh))

  section('Leaving a group')
  await http('DELETE', `/api/conversations/${group.id}/members/me`, { token: carol.token })
  const again2 = await http('DELETE', `/api/conversations/${group.id}/members/me`, { token: carol.token })
  check('carol leaves; leaving twice is a no-op (204)', again2.status === 204)
  const gone = await http('GET', `/api/conversations/${group.id}`, { token: carol.token })
  check('…and she can no longer open it', gone.status === 404, `${gone.status}`)

  section('Rate limiting (gateway + Redis)')
  // Each request claims a different X-Forwarded-For: nginx overwrites it with the real address,
  // so clients can't dodge the limit by spoofing it.
  const tries = await Promise.all(Array.from({ length: 15 }, (_, i) =>
    http('POST', '/api/auth/login', { noRetry: true, headers: { 'X-Forwarded-For': `10.77.0.${i}` }, body: { username: 'nobody', password: 'x' } })))
  check('15 rapid logins → some get 429, even with spoofed X-Forwarded-For', tries.some((t) => t.status === 429), JSON.stringify(tries.map((t) => t.status)))

  ;[aliceWs, bobWs, bobNotifWs].forEach((c) => c.close())
  console.log(`\n${failed ? '\x1b[31m' : '\x1b[32m'}${passed} passed, ${failed} failed\x1b[0m`)
  process.exit(failed ? 1 : 0)
}

main().catch((e) => {
  console.error('\n\x1b[31mE2E crashed:\x1b[0m', e)
  process.exit(1)
})
