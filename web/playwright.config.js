import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  timeout: 45_000,
  expect: { timeout: 20_000 },
  workers: 1,
  use: { baseURL: 'http://127.0.0.1:15173', browserName: 'chromium', trace: 'retain-on-failure' },
  webServer: {
    command: 'node e2e/server.mjs',
    url: 'http://127.0.0.1:15173',
    reuseExistingServer: false,
    timeout: 120_000,
    gracefulShutdown: { signal: 'SIGTERM', timeout: 5_000 },
  },
})
