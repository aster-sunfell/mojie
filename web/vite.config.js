import { defineConfig } from 'vite'

export default defineConfig({
  server: {
    hmr: { clientPort: Number(process.env.MOJIE_HMR_CLIENT_PORT || 8088) },
    proxy: { '/api': process.env.MOJIE_API_URL || 'http://127.0.0.1:8080' }
  }
})
