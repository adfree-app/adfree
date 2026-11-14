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

async function open({ installed = [], onCall, html = PAGE, site = SITE, path = '/en/scripts/550659-find-scripts-for-this-site' } = {}) {
  const context = await browser.newContext();
  contexts.push(context);
  const page = await context.newPage();
  const calls = [];
  await page.route(`${site}/**`, (route) => route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: html }));
  await page.route(`${API}/**`, async (route) => {
    const body = route.request().postDataJSON();
    calls.push({ url: route.request().url(), body });
    const answer = onCall ? onCall(route.request().url(), body) : { ok: true, id: 'store-1', name: 'Find Scripts For This Site', version: '0.4.3' };
    await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(answer) });
  });
  await page.goto(`${site}${path}`);
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

  /// A store that names nothing Greasy Fork names: ScriptCat's control is an Ant
  /// Design button in a compact group and the version lives in its label. The
  /// button finds it by what it links to — a userscript — not by the markup.
  test('a second store is found by the link it offers, not by its markup', async () => {
    const SC = 'https://scriptcat.org';
    const script = '/scripts/code/58/hook demo.user.js';
    const html = `<!doctype html>
<html lang="en"><head><title>Script</title></head><body>
<div class="ant-space-compact w-full">
  <a class="ant-btn ant-btn-primary" href="${script}" style="display:none"><span>Install Script · v0.1</span></a>
  <a class="ant-btn ant-btn-primary" href="${script}"><span>Install Script · v0.1</span></a>
  <button class="ant-btn">Link</button>
</div>
</body></html>`;
    const { page, calls } = await open({
      site: SC,
      html,
      path: '/en/script-show-page/58',
      // The version the label carried has to survive the round trip, or the
      // page immediately offers an update it just performed.
      onCall: () => ({ ok: true, id: 'sc-1', name: 'hook demo', version: '0.1' }),
    });

    const button = page.locator('#adfree-store-button');
    await expect.poll(() => button.count()).toBe(1);
    expect(await button.textContent()).toContain('Install with AdFree');
    // Styled by the page: an Ant Design control beside a plain link reads as a
    // broken page.
    expect(await button.getAttribute('class')).toContain('ant-btn');
    // After the control a reader sees, not the phone layout that has no box.
    const after = await button.evaluate((el) => ({
      prev: el.previousElementSibling?.getAttribute('class') || '',
      prevWidth: el.previousElementSibling?.getBoundingClientRect().width || 0,
    }));
    expect(after.prev).toContain('ant-btn');
    expect(after.prevWidth).toBeGreaterThan(0);

    await button.click();
    await expect.poll(() => button.textContent()).toContain('Installed');
    // The path the page wrote, made whole: the app downloads this URL.
    expect(calls[0].body.url).toBe(`${SC}/scripts/code/58/hook%20demo.user.js`);
    // Same identity the app keys on, so a rename in the slug is still an update.
    expect(calls[0].body.url.includes('/scripts/code/58/')).toBe(true);
  });

  /// Where the proxy actually puts the tag: the end of `<head>`, before the
  /// document has a body. The first version gave up there — the install area
  /// did not exist yet — and the button never reached a real Greasy Fork page.
  test('a tag in the head waits for the install area', async () => {
    const context = await browser.newContext();
    contexts.push(context);
    const page = await context.newPage();
    const head = `<!doctype html><html lang="vi"><head><meta charset="utf-8">
<script>window.__adfree_store=${JSON.stringify({ api: API, host: 'greasyfork.org', token: TOKEN, installed: [] })};</script>
<script src="${SITE}/adfree-store.js"></script>
</head><body>${PAGE.slice(PAGE.indexOf('<body>') + 6, PAGE.indexOf('</body>'))}</body></html>`;
    await page.route(`${SITE}/**`, (route) =>
      route.request().url().endsWith('/adfree-store.js')
        ? route.fulfill({ status: 200, contentType: 'application/javascript; charset=utf-8', body: STORE })
        : route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: head }),
    );
    await page.goto(`${SITE}/en/scripts/550659-find-scripts-for-this-site`);
    const button = page.locator('#adfree-store-button');
    await expect.poll(() => button.count()).toBe(1);
    expect(await button.textContent()).toContain('Cài đặt');
    // Still between the site's own link and the help icon.
    const order = await page.$$eval('#install-area > *', (nodes) => nodes.map((n) => n.id || n.className));
    expect(order[1]).toContain('adfree-store-button');
  });

  /// A store page may repaint the install area, which takes our node with it.
  test('the button comes back after the site repaints its install area', async () => {
    const { page } = await open();
    await expect.poll(() => page.locator('#adfree-store-button').count()).toBe(1);
    await page.evaluate(() => {
      const area = document.getElementById('install-area');
      area.replaceChildren(...area.children);
    });
    await expect.poll(() => page.locator('#adfree-store-button').count()).toBe(1);
  });
});
