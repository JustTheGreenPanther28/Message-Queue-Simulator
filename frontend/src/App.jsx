import { useCallback, useEffect, useState } from 'react'
import AuthScreen from './components/AuthScreen.jsx'
import ChatApp from './components/ChatApp.jsx'
import { setAuth } from './lib/api.js'
import { ACCENTS } from './lib/format.js'

const SESSION_KEY = 'tilechat.session'
const PREFS_KEY = 'tilechat.prefs'

function loadJson(key) {
  try { return JSON.parse(localStorage.getItem(key)) } catch { return null }
}

export default function App() {
  const [session, setSession] = useState(() => loadJson(SESSION_KEY))
  const [prefs, setPrefs] = useState(() => ({
    theme: window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light',
    accent: ACCENTS[0].value,
    ...loadJson(PREFS_KEY),
  }))

  useEffect(() => {
    document.documentElement.dataset.theme = prefs.theme
    document.documentElement.style.setProperty('--accent', prefs.accent)
    localStorage.setItem(PREFS_KEY, JSON.stringify(prefs))
  }, [prefs])

  const signOut = useCallback(() => {
    localStorage.removeItem(SESSION_KEY)
    setAuth(null)
    setSession(null)
  }, [])

  // Set synchronously so the very first requests of ChatApp carry the token.
  setAuth(session?.token ?? null, signOut)

  const onAuthenticated = (res) => {
    const s = { token: res.token, user: res.user }
    localStorage.setItem(SESSION_KEY, JSON.stringify(s))
    setSession(s)
  }

  const updateUser = useCallback((user) => {
    setSession((s) => {
      const next = { ...s, user }
      localStorage.setItem(SESSION_KEY, JSON.stringify(next))
      return next
    })
  }, [])

  const toggleTheme = () => setPrefs((p) => ({ ...p, theme: p.theme === 'dark' ? 'light' : 'dark' }))

  if (!session) return <AuthScreen onAuthenticated={onAuthenticated} theme={prefs.theme} onToggleTheme={toggleTheme} />
  return (
    <ChatApp
      key={session.user.id}
      token={session.token}
      initialUser={session.user}
      onUserChange={updateUser}
      onSignOut={signOut}
      prefs={prefs}
      setPrefs={setPrefs}
    />
  )
}
