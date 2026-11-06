#!/usr/bin/env node
// Creates demo accounts with a little history, so the UI has something to show.
//   node scripts/seed-demo.mjs     (stack must be running; safe to re-run)
// Local test accounts only. Password for all of them: demo1234
const BASE = process.env.BASE_URL || 'http://localhost:3000'
const PASSWORD = 'demo1234'
const people = [
  ['demo_alice', 'Alice Martin'], ['demo_bob', 'Bob Chen'], ['demo_carol', 'Carol Diaz'], ['demo_dave', 'Dave Okafor'],
]

async function call(method, path, token, body, headers = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  return { status: res.status, data: text ? JSON.parse(text) : null }
}

const users = {}
for (const [username, displayName] of people) {
  let r = await call('POST', '/api/auth/register', null, { username, displayName, password: PASSWORD })
  if (r.status === 409) r = await call('POST', '/api/auth/login', null, { username, password: PASSWORD })
  if (!r.data?.token) throw new Error(`could not create ${username}: ${JSON.stringify(r.data)}`)
  users[username] = { token: r.data.token, ...r.data.user }
}
await new Promise((r) => setTimeout(r, 1500)) // let profiles reach chat-service

const { demo_alice: a, demo_bob: b, demo_carol: c, demo_dave: d } = users
const say = (from, conv, text, key) =>
  call('POST', `/api/conversations/${conv}/messages`, from.token, { clientMessageId: `seed-${key}`, content: text })

const accept = (who, conv) => call('POST', `/api/conversations/${conv}/accept`, who.token)

const ab = (await call('POST', '/api/conversations/direct', a.token, { userId: b.id })).data
await accept(b, ab.id)
await say(b, ab.id, 'Hey Alice! Are we still on for Friday?', 'ab1')
await say(a, ab.id, 'Yes! 7pm at the usual place 🍜', 'ab2')
await say(b, ab.id, 'Perfect, see you there', 'ab3')
await call('POST', `/api/conversations/${ab.id}/read`, a.token)

const team = (await call('POST', '/api/conversations/group', a.token, { name: 'Weekend trip', memberIds: [b.id, c.id, d.id] },
  { 'Idempotency-Key': 'seed-weekend-trip' })).data
for (const who of [b, c, d]) await accept(who, team.id)
await say(c, team.id, 'I booked the cabin for Saturday 🎉', 't1')
await say(d, team.id, 'Amazing. I can drive, there is room for three', 't2')
await say(b, team.id, 'Count me in', 't3')

const ac = (await call('POST', '/api/conversations/direct', c.token, { userId: a.id })).data
await accept(a, ac.id)
await say(c, ac.id, 'Can you send me the photos from last week?', 'ac1')

// Left unanswered so the Requests tab has something: a message request and a group invite.
const da = (await call('POST', '/api/conversations/direct', d.token, { userId: a.id })).data
await say(d, da.id, 'Hi Alice, Carol gave me your username. Got a minute?', 'da1')
await call('POST', '/api/conversations/group', b.token, { name: 'Book club', memberIds: [a.id, c.id] }, { 'Idempotency-Key': 'seed-book-club' })

await call('POST', '/api/messages/delivered', b.token, {})
console.log(`Seeded. Sign in at ${BASE} as demo_alice / demo_bob / demo_carol / demo_dave (password: ${PASSWORD}).`)
