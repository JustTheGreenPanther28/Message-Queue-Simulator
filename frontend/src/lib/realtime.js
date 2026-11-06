import { Client, ReconnectionTimeMode } from '@stomp/stompjs'

/**
 * Opens a STOMP connection that reconnects with exponential backoff. `onReconnect`
 * fires on every connect after the first, so the app can fetch anything it missed.
 * Returns a function that closes the connection.
 */
export function connectSocket({ path, token, subscriptions, onStatus, onReconnect, onAuthError }) {
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  let connectedBefore = false

  const client = new Client({
    brokerURL: `${proto}://${window.location.host}${path}`,
    connectHeaders: { Authorization: `Bearer ${token}` },
    reconnectDelay: 1000,
    maxReconnectDelay: 15000,
    reconnectTimeMode: ReconnectionTimeMode.EXPONENTIAL,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    onConnect: () => {
      for (const [destination, handler] of Object.entries(subscriptions)) {
        client.subscribe(destination, (frame) => handler(JSON.parse(frame.body)))
      }
      onStatus?.('online')
      if (connectedBefore) onReconnect?.()
      connectedBefore = true
    },
    onWebSocketClose: () => onStatus?.('offline'),
    onStompError: (frame) => {
      if (/token|auth/i.test(frame.headers?.message || '')) onAuthError?.()
    },
  })
  client.activate()
  return () => client.deactivate()
}
