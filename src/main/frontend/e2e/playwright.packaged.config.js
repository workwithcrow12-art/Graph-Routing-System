import { defineConfig } from '@playwright/test';
import { delimiter, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import devConfig from '../playwright.config.js';

const pathKey = process.platform === 'win32'
  ? Object.keys(process.env).find((name) => name.toUpperCase() === 'PATH') ?? 'PATH'
  : 'PATH';
const javaEnv = process.env.JAVA_HOME
  ? { [pathKey]: [resolve(process.env.JAVA_HOME, 'bin'), process.env[pathKey]].filter(Boolean).join(delimiter) }
  : undefined;

export default defineConfig({
  ...devConfig,
  testDir: '.',
  outputDir: '../test-results',
  globalSetup: './packaged-ready.js',
  use: {
    ...devConfig.use,
    // Playwright's baseURL fixture reads the environment value captured below.
    baseURL: undefined,
    trace: 'retain-on-failure',
  },
  webServer: {
    name: 'Packaged app',
    command: 'java -jar graphrouting-system-1.0-SNAPSHOT-jar-with-dependencies.jar serve --port 0',
    env: javaEnv,
    // A working directory outside the source tree prevents fallback to frontend/dist.
    cwd: process.env.PLAYWRIGHT_JAR_DIR
      ? resolve(process.env.PLAYWRIGHT_JAR_DIR)
      : fileURLToPath(new URL('../../../../target/', import.meta.url)),
    wait: { stdout: /Routing UI and API listening at (?<PLAYWRIGHT_TEST_BASE_URL>http:\/\/127\.0\.0\.1:\d+)\r?\n/ },
    stdout: 'pipe',
    gracefulShutdown: { signal: 'SIGTERM', timeout: 5000 },
  },
});
