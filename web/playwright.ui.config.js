import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: './e2e',
  testMatch: ['home.spec.js', 'workspace.spec.js'],
  timeout: 20_000,
  expect: { timeout: 5_000 },
  workers: 1,
  use: { baseURL: 'http://127.0.0.1:15174', browserName: 'chromium', trace: 'retain-on-failure' },
  webServer: {
    command: 'npm run dev -- --port 15174 --strictPort',
    url: 'http://127.0.0.1:15174',
    env: { MOJIE_HMR_CLIENT_PORT: '15174' },
    reuseExistingServer: !process.env.CI,
  },
})
