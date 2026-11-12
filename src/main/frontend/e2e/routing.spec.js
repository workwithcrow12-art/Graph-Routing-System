import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

function nextPost(page, path) {
  return page.waitForResponse((response) =>
    new URL(response.url()).pathname === path && response.request().method() === 'POST');
}

async function successfulPost(page, path, submit) {
  const pending = nextPost(page, path);
  await submit();
  const response = await pending;
  const payload = await response.json();
  expect(response.ok(), `${path} returned HTTP ${response.status()}`).toBe(true);
  return payload;
}

async function routeWithQuotaRetry(page, response) {
  let payload = await response.json();
  if (response.status() === 422 && payload.error?.code === 'ROUTE_LIMIT_EXCEEDED') {
    const pending = nextPost(page, '/api/routes');
    await page.getByRole('button', { name: 'Thử lại chỉ với tuyến chính' }).click();
    const retry = await pending;
    expect(retry.request().postDataJSON().alternatives).toBe(1);
    payload = await retry.json();
    expect(retry.ok(), payload.error?.message).toBe(true);
    expect(payload.routes).toHaveLength(1);
    await expect(page.getByRole('alert')).toHaveCount(0);
  } else {
    expect(response.ok(), payload.error?.message).toBe(true);
  }
  return payload;
}

test('the compact tablet toolbar exposes the analysis button by name', async ({ page }) => {
  await page.setViewportSize({ width: 900, height: 1000 });
  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Thử một hành trình' })).toBeEnabled();
  const tabletAxe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(tabletAxe.violations).toEqual([]);
  await page.getByRole('button', { name: 'Phân tích thuật toán' }).click();
  await expect(page.getByRole('main', { name: 'Phân tích thuật toán' })).toBeVisible();
});

test('real routing, alternative selection, walking and algorithm comparison work at desktop and mobile sizes', async ({ page }) => {
  test.slow(process.platform === 'win32', 'Software-rendered maps make this full journey slower on Windows.');
  const errors = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await page.goto('/');
  await expect(page.getByRole('button', { name: 'Thử một hành trình' })).toBeEnabled();
  await page.getByLabel('Điểm đi', { exact: true }).fill('Ngõ 90 Đường Khuất Duy Tiến');
  await page.locator('.endpoint-fields .search-results button').first().click();
  await page.getByLabel('Điểm đến', { exact: true }).fill('Ngõ 178 Phố Quan Nhân');
  await page.locator('.endpoint-fields .search-results button').first().click();
  const motorcycleRequest = nextPost(page, '/api/routes');
  await page.getByRole('button', { name: 'Tìm đường', exact: true }).click();
  const motorcycleResponse = await motorcycleRequest;
  expect(motorcycleResponse.ok()).toBe(true);
  const motorcycle = await motorcycleResponse.json();
  expect(motorcycle.routes).toHaveLength(3);
  const cards = page.locator('.route-card');
  await expect(cards).toHaveCount(3);
  await expect(page.locator('.maplibregl-canvas')).toBeVisible();
  await expect(cards.first()).toHaveAttribute('aria-pressed', 'true');
  await cards.nth(1).click();
  await expect(cards.nth(1)).toHaveAttribute('aria-pressed', 'true');
  await expect(cards.first()).toHaveAttribute('aria-pressed', 'false');
  await expect(page.locator('.steps-card .directions li').first()).toContainText('Xuất phát');
  await page.getByRole('button', { name: 'Bắt đầu đi', exact: true }).click();
  await expect(page.getByRole('region', { name: 'Chỉ dẫn hiện tại' })).toBeVisible();
  await page.getByRole('button', { name: 'Kết thúc chỉ dẫn' }).click();
  await page.getByRole('button', { name: 'Đi bộ', exact: true }).click();
  await expect(cards).toHaveCount(0);
  const walkingRequest = nextPost(page, '/api/routes');
  await page.getByRole('button', { name: 'Tìm đường', exact: true }).click();
  const walkingResponse = await walkingRequest;
  const walking = await routeWithQuotaRetry(page, walkingResponse);
  expect(walking.mode).toBe('walking');
  expect(walking.routes[0].durationSeconds).not.toBe(motorcycle.routes[0].durationSeconds);
  await expect(page.locator('.route-card').first()).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const desktopAxe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(desktopAxe.violations).toEqual([]);
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.getByRole('button', { name: /\d+ bước/ }).click();
  await expect(page.locator('.mobile-step-list')).toBeVisible();
  await page.getByRole('button', { name: 'Thu gọn bảng chỉ đường' }).click();
  await page.getByRole('button', { name: 'Thuật toán', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Chạy thuật toán', exact: true })).toBeEnabled();
  await successfulPost(page, '/api/algorithms/run', () =>
    page.getByRole('button', { name: 'Chạy thuật toán', exact: true }).click());
  await expect(page.locator('.analysis-bar-row').first()).toBeVisible();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.getByRole('button', { name: 'A*', exact: true }).click();
  await successfulPost(page, '/api/algorithms/run', () =>
    page.getByRole('button', { name: 'Chạy thuật toán', exact: true }).click());
  await expect(page.locator('.analysis-bar-row')).toHaveCount(2);
  const metrics = await page.locator('.analysis-bar-row strong').allTextContents();
  expect(metrics[0]).not.toEqual(metrics[1]);
  await expect(page.getByRole('button', { name: 'Floyd-Warshall', exact: true })).toBeDisabled();
  const analysisAxe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze();
  expect(analysisAxe.violations).toEqual([]);
  await page.getByRole('button', { name: 'Trở về bản đồ' }).click();
  await expect(page.locator('.route-card').first()).toBeVisible();
  expect(errors).toEqual([]);
});

for (const forceRouteLimit of [false, true]) {
test(forceRouteLimit
  ? 'traffic routing recovers from search limits by requesting real primary routes'
  : 'saving traffic clears the stale route and a new route uses the observation', async ({ page }) => {
  if (forceRouteLimit) {
    await page.route('**/api/routes', async (route) => {
      if (route.request().postDataJSON().alternatives > 1) {
        await route.fulfill({ status: 422, json: {
          error: { code: 'ROUTE_LIMIT_EXCEEDED', message: 'Search exceeded its time limit' },
        } });
      } else await route.continue();
    });
  }
  await page.goto('/');
  const originalRequest = nextPost(page, '/api/routes');
  await page.getByRole('button', { name: 'Thử một hành trình' }).click();
  const originalResponse = await originalRequest;
  if (forceRouteLimit) expect(originalResponse.status()).toBe(422);
  const { routes: [originalRoute] } = await routeWithQuotaRetry(page, originalResponse);
  const affectedSegment = originalRoute.segments[0].id;
  const cards = page.locator('.route-card');
  await expect(cards.first()).toBeVisible();

  await page.getByRole('button', { name: 'Giao thông', exact: true }).click();
  const traffic = page.getByRole('region', { name: 'Giao thông' });
  await traffic.getByLabel('Lọc cung đường').fill(affectedSegment);
  await traffic.getByLabel(/Cung đường \(/).selectOption(affectedSegment);
  await traffic.getByLabel('Đóng đoạn đường').check();
  // Give rendering its own assertion budget after the save response is consumed.
  const saved = await successfulPost(page, '/api/traffic/update', () =>
    traffic.getByRole('button', { name: 'Lưu tình trạng' }).click());
  expect(saved).toMatchObject({ active: true, updates: 1 });
  await expect(traffic.getByText(/Đang áp dụng 1 quan sát/)).toBeVisible();
  await traffic.getByRole('button', { name: 'Đóng giao thông' }).click();
  await expect(cards).toHaveCount(0);

  const updatedRequest = nextPost(page, '/api/routes');
  await page.getByRole('button', { name: 'Tìm đường', exact: true }).click();
  const updatedResponse = await updatedRequest;
  if (forceRouteLimit) expect(updatedResponse.status()).toBe(422);
  const updated = await routeWithQuotaRetry(page, updatedResponse);
  expect(updated.traffic).toMatchObject({ active: true, updates: 1 });
  expect(updated.routes[0].segments.map(({ id }) => id)).not.toContain(affectedSegment);
  await expect(cards.first()).toBeVisible();

  await page.getByRole('button', { name: 'Giao thông', exact: true }).click();
  const clearRequest = nextPost(page, '/api/traffic/update');
  await traffic.getByRole('button', { name: 'Xóa snapshot' }).click();
  const cleared = await clearRequest;
  expect(cleared.ok()).toBe(true);
  expect(await cleared.json()).toMatchObject({ active: false, updates: 0 });
  await expect(traffic.getByText('Đã xóa snapshot giao thông.')).toBeVisible();
  await expect(traffic.getByText('Chưa có quan sát giao thông đang hiệu lực.')).toBeVisible();
});
}

test('custom graph edits show a rejected duplicate and recover on the next valid edit', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: 'Phân tích thuật toán' }).click();
  const settings = page.getByRole('complementary', { name: 'Thiết lập và so sánh thuật toán' });
  await settings.getByText('OSM Thanh Xuân', { exact: true }).click();
  await settings.getByRole('combobox', { name: 'Đồ thị phân tích', exact: true }).selectOption('custom');
  const editor = page.getByRole('region', { name: 'Chỉnh graph custom' });
  const nodeId = 'browser-test-junction';
  const addNode = editor.getByRole('button', { name: 'Thêm nút' });
  await editor.getByLabel('ID nút').fill(nodeId);
  await editor.getByLabel('Tên nút').fill('Nút thử nghiệm');
  await successfulPost(page, '/api/analysis/graph/edit', () => addNode.click());
  const nodes = editor.getByLabel('Nút cần xóa');
  const newNode = nodes.getByRole('option', { name: `Nút thử nghiệm (${nodeId})` });
  await expect(newNode).toHaveCount(1);

  await editor.getByLabel('ID nút').fill(nodeId);
  await editor.getByLabel('Tên nút').fill('Nút trùng');
  const duplicateRequest = nextPost(page, '/api/analysis/graph/edit');
  await addNode.click();
  const duplicate = await duplicateRequest;
  expect(duplicate.status()).toBe(400);
  await expect(page.getByRole('alert')).toContainText('Node ID must be new');
  await expect(newNode).toHaveCount(1);

  await nodes.selectOption(nodeId);
  await successfulPost(page, '/api/analysis/graph/edit', () =>
    editor.getByRole('button', { name: 'Xóa nút' }).click());
  await expect(newNode).toHaveCount(0);
  await expect(page.getByRole('alert')).toHaveCount(0);
});
