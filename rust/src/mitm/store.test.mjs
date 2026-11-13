// Tests for `store.js`, the script injected into a script-repository page so the
// site's own "Install this script" link gains an AdFree control beside it.
//
// Real Chromium, as with `page_runtime.test.mjs`: the button is DOM work
// (`#install-area`, sibling order, `MutationObserver` timing) and a stub DOM
// would only prove the stub works.
//
//   bun install && bun run test
//
// The app's endpoint is answered by `page.route`, so the test asserts the exact
// request the page sends — path, origin, token, URL — and the states the button
// moves through.

import { readFileSync } from 'node:fs';
import { chromium } from 'playwright';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';

const STORE = readFileSync(new URL('../../assets/store.js', import.meta.url), 'utf8');

const SITE = 'https://greasyfork.org';
const API = 'http://127.0.0.1:3130/userscript';
const TOKEN = 'test-token';
const INSTALL_URL =
  'https://update.greasyfork.org/scripts/550659/Find%20Scripts%20For%20This%20Site.user.js';

const PAGE = `<!doctype html>
<html lang="vi"><head><title>Script</title></head><body>
<div id="script-content">
  <div id="install-area">
    <a class="install-link" data-script-id="550659"
       data-script-name="Find Scripts For This Site" data-script-version="0.4.3"
       href="${INSTALL_URL}">Install this script</a>
    <a class="install-help-link" href="/help">?</a>
  </div>
</div>
</body></html>`;

let browser = null;
const contexts = [];

beforeAll(async () => {
  // The system Chrome, as `page_runtime.test.mjs` does; `PLAYWRIGHT_CHANNEL`
  // switches to the browser `bunx playwright install chromium` fetched.
  browser = await chromium.launch({ channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome' });
}, 60_000);

afterAll(async () => {
  for (const context of contexts) await context.close();
  if (browser) await browser.close();
});

async function open({ installed = [], onCall } = {}) {
  const context = await browser.newContext();
  contexts.push(context);
  const page = await context.newPage();
  const calls = [];
  await page.route(`${SITE}/**`, (route) => route.fulfill({ status: 200, contentType: 'text/html', body: PAGE }));
  await page.route(`${API}/**`, async (route) => {
    const body = route.request().postDataJSON();
    calls.push({ url: route.request().url(), body });
    const answer = onCall ? onCall(route.request().url(), body) : { ok: true, id: 'store-1', name: 'Find Scripts For This Site', version: '0.4.3' };
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(answer) });
  });
  await page.goto(`${SITE}/en/scripts/550659-find-scripts-for-this-site`);
  await page.evaluate(
    ([api, token, list]) => {
      window.__adfree_store = { api, host: 'greasyfork.org', token, installed: list };
    },
    [API, TOKEN, installed],
  );
  await page.addScriptTag({ content: STORE });
  return { page, calls };
}

describe('the store button', () => {
  test('sits next to the site install link and installs on click', async () => {
    const { page, calls } = await open();

    const button = page.locator('#adfree-store-button');
    await expect.poll(() => button.count()).toBe(1);
    expect(await button.textContent()).toContain('Cài đặt');
    // Between the site's own link and the help icon.
    const order = await page.$$eval('#install-area > *', (nodes) => nodes.map((n) => n.id || n.className));
    expect(order[1]).toContain('adfree-store-button');

    await button.click();
    await expect.poll(() => button.textContent()).toContain('Đã cài đặt');
    expect(calls).toHaveLength(1);
    expect(calls[0].url).toBe(`${API}/install`);
    expect(calls[0].body.token).toBe(TOKEN);
    expect(calls[0].body.url).toBe(INSTALL_URL);
    await expect.poll(() => page.locator('#adfree-store-uninstall').count()).toBe(1);
  });

  test('reads as installed when the app already has this script', async () => {
    const { page } = await open({
      installed: [{ id: 'store-1', name: 'Find Scripts For This Site', url: INSTALL_URL, key: 'greasyfork:550659', version: '0.4.3' }],
    });
    const button = page.locator('#adfree-store-button');
    await expect.poll(() => button.textContent()).toContain('Đã cài đặt');
    expect(await page.locator('#adfree-store-uninstall').count()).toBe(1);
  });

  test('offers an update when the stored copy is older', async () => {
    const { page, calls } = await open({
      installed: [{ id: 'store-1', name: 'Find Scripts For This Site', url: INSTALL_URL, key: 'greasyfork:550659', version: '0.3.0' }],
    });
    const button = page.locator('#adfree-store-button');
    await expect.poll(() => button.textContent()).toContain('Cập nhật');
    await button.click();
    await expect.poll(() => button.textContent()).toContain('Đã cài đặt');
    expect(calls[0].url).toBe(`${API}/install`);
  });

  test('uninstall goes back to the install state', async () => {
    const { page, calls } = await open({
      installed: [{ id: 'store-1', name: 'Find Scripts For This Site', url: INSTALL_URL, key: 'greasyfork:550659', version: '0.4.3' }],
      onCall: () => ({ ok: true, name: 'Find Scripts For This Site' }),
    });
    await page.locator('#adfree-store-uninstall').click();
    await expect.poll(() => page.locator('#adfree-store-button').textContent()).toContain('Cài đặt');
    expect(calls.map((c) => c.url)).toContain(`${API}/uninstall`);
  });

  test('a refused install says so and leaves the button usable', async () => {
    const { page } = await open({
      onCall: () => ({ ok: false, error: 'not a userscript' }),
    });
    const button = page.locator('#adfree-store-button');
    await button.click();
    await expect.poll(() => page.locator('#adfree-store-note').textContent()).toContain('Không cài được');
    expect(await button.textContent()).toContain('Cài đặt');
  });

  test('a page without an install area is left alone', async () => {
    const context = await browser.newContext();
    contexts.push(context);
    const page = await context.newPage();
    await page.route(`${SITE}/**`, (route) =>
      route.fulfill({ status: 200, contentType: 'text/html', body: '<html><body><p>no script here</p></body></html>' }),
    );
    await page.goto(`${SITE}/en/scripts`);
    await page.evaluate(([api, token]) => {
      window.__adfree_store = { api, host: 'greasyfork.org', token, installed: [] };
    }, [API, TOKEN]);
    await page.addScriptTag({ content: STORE });
    expect(await page.locator('#adfree-store-button').count()).toBe(0);
  });
});
