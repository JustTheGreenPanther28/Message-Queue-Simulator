import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// In dev, everything goes through the API gateway (same origin, so no CORS).
const gateway = process.env.GATEWAY_URL || 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': gateway,
      '/ws': { target: gateway.replace(/^http/, 'ws'), ws: true }, // also covers /ws-notifications
      // Same-origin object storage, like nginx does in Docker. changeOrigin sends Host:
      // localhost:8333, which is the host the services sign URLs for in dev.
      '/storage': {
        target: process.env.STORAGE_URL || 'http://localhost:8333',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/storage/, ''),
      },
    },
  },
  test: { environment: 'node' },
})
