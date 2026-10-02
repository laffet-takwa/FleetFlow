import { fileURLToPath, URL } from 'node:url'

import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// The SPA talks to the API gateway, never to an individual service: in development
// Vite proxies the gateway's paths so the browser sees a single origin, which keeps
// cookies, CORS and the SSE streams behaving exactly as they do behind nginx.
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    port: 5173,
    strictPort: false,
    proxy: {
      '/api': {
        target: process.env.VITE_DEV_GATEWAY_URL ?? 'http://localhost:8080',
        changeOrigin: true,
        // Server-Sent Events must stream through unbuffered, otherwise live tracking
        // receives everything at once when the connection closes.
        configure: (proxy) => {
          proxy.on('proxyRes', (proxyRes) => {
            if (proxyRes.headers['content-type']?.includes('text/event-stream')) {
              proxyRes.headers['x-accel-buffering'] = 'no'
            }
          })
        },
      },
      '/actuator': {
        target: process.env.VITE_DEV_GATEWAY_URL ?? 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    target: 'es2020',
    outDir: 'dist',
    sourcemap: false,
    chunkSizeWarningLimit: 900,
    rollupOptions: {
      output: {
        // Leaflet and the map code are only needed on the tracking screens, so they
        // are kept out of the initial bundle for every other route.
        manualChunks: {
          vue: ['vue', 'vue-router', 'pinia'],
          leaflet: ['leaflet'],
        },
      },
    },
  },
})
