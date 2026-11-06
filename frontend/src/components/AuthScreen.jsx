import { useState } from 'react'
import { api } from '../lib/api.js'
import { MoonIcon, SunIcon } from './Icons.jsx'

export default function AuthScreen({ onAuthenticated, theme, onToggleTheme }) {
  const [mode, setMode] = useState('login')
  const [form, setForm] = useState({ username: '', displayName: '', password: '' })
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value })
  const isRegister = mode === 'register'

  async function submit(e) {
    e.preventDefault()
    setError('')
    setBusy(true)
    try {
      const res = isRegister
        ? await api.register(form.username.trim(), form.displayName.trim() || form.username.trim(), form.password)
        : await api.login(form.username.trim(), form.password)
      onAuthenticated(res)
    } catch (err) {
      setError(err.message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth">
      <section className="auth-hero">
        <div>
          <h1 className="title-xl">tile chat</h1>
          <p style={{ fontSize: 20, fontWeight: 300, opacity: 0.9, maxWidth: 420 }}>
            Talk to the people you care about. One to one, or everyone at once.
          </p>
        </div>
        <div className="auth-tiles">
          {Array.from({ length: 12 }, (_, i) => <span key={i} style={{ animationDelay: `${i * 40}ms` }} />)}
        </div>
      </section>

      <section className="auth-panel" style={{ position: 'relative' }}>
        <button className="icon-btn auth-theme" onClick={onToggleTheme} title="Switch theme" aria-label="Switch theme">
          {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
        </button>
        <h2 className="title-l" key={mode}>{isRegister ? 'create account' : 'sign in'}</h2>
        <form onSubmit={submit} key={mode + '-form'}>
          <div className="field">
            <label htmlFor="username">Username</label>
            <input id="username" className="input" autoComplete="username" autoFocus value={form.username}
                   onChange={set('username')} required minLength={isRegister ? 3 : 1} maxLength={32} />
          </div>
          {isRegister && (
            <div className="field">
              <label htmlFor="displayName">Display name</label>
              <input id="displayName" className="input" autoComplete="name" value={form.displayName}
                     onChange={set('displayName')} maxLength={40} placeholder="How others see you" />
            </div>
          )}
          <div className="field">
            <label htmlFor="password">Password</label>
            <input id="password" className="input" type="password" value={form.password} onChange={set('password')}
                   autoComplete={isRegister ? 'new-password' : 'current-password'} required minLength={isRegister ? 8 : 1} />
          </div>
          <div className="error-text" role="alert">{error}</div>
          <div style={{ display: 'flex', gap: 16, alignItems: 'center' }}>
            <button className="btn btn-accent" disabled={busy}>
              {busy ? <span className="spinner"><i /><i /><i /></span> : isRegister ? 'Create account' : 'Sign in'}
            </button>
            <button type="button" className="auth-switch" onClick={() => { setMode(isRegister ? 'login' : 'register'); setError('') }}>
              {isRegister ? 'I already have an account' : 'Create an account'}
            </button>
          </div>
        </form>
      </section>
    </div>
  )
}
