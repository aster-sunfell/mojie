import { defineConfig, searchForWorkspaceRoot } from 'vite'
import { fileURLToPath } from 'node:url'

export default defineConfig({
  server: {
    fs: { allow: [searchForWorkspaceRoot(fileURLToPath(new URL('.', import.meta.url))),
      fileURLToPath(new URL('../docs/user-guide', import.meta.url))] },
    hmr: { clientPort: Number(process.env.MOJIE_HMR_CLIENT_PORT || 8088) },
    proxy: { '/api': process.env.MOJIE_API_URL || 'http://127.0.0.1:8080' }
  }
})
