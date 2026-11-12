import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.js',
  timeout: 120000,
  expect: { timeout: 15000 },
  workers: 1,
  use: {
    baseURL: 'http://localhost:5173',
    viewport: { width: 1440, height: 1000 },
    reducedMotion: 'reduce',
    screenshot: 'only-on-failure',
    launchOptions: {
      executablePath: process.env.PLAYWRIGHT_CHROMIUM_EXECUTABLE,
      args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader'],
    },
  },
  webServer: { command: 'npm run dev', url: 'http://localhost:5173', reuseExistingServer: true },
});
