export default async function checkPackagedApp() {
  const baseURL = process.env.PLAYWRIGHT_TEST_BASE_URL;
  const health = await fetch(new URL('/api/health', baseURL), { signal: AbortSignal.timeout(5000) });
  if (!health.ok || (await health.json()).status !== 'ok') {
    throw new Error(`Packaged app did not report readiness at ${health.url} (HTTP ${health.status})`);
  }
  const ui = await fetch(new URL('/', baseURL), {
    headers: { Accept: 'text/html' },
    signal: AbortSignal.timeout(5000),
  });
  if (!ui.ok || !ui.headers.get('content-type')?.startsWith('text/html')) {
    throw new Error(`Packaged app did not serve HTML at ${ui.url} (HTTP ${ui.status})`);
  }
  await ui.body.cancel();
}
