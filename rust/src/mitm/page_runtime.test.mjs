// Tests for `page_runtime.js`, the script the app injects into every page it
// filters. They run the real file in a real Chromium (Playwright): a stub DOM
// cannot speak for computed styles, `MutationObserver` timing, constructable
// stylesheets or XPath, and the runtime lives or dies on exactly those.
//
//   bun install && bun run test        (bun is the package manager here)
//
// The system Chrome is used; `PLAYWRIGHT_CHANNEL=chromium` switches to the
// browser `bunx playwright install chromium` fetched.
//
// The proxy is not part of these tests: `page.route` answers for both origins
// instead — the site, and the app's own injection origin under `/local` — so a
// test can hand the runtime any payload and any lookup answer it likes. The end-to-end
// path through the real VPN is checked on the emulator (docs/injection.md);
// a Chrome already running behind it can be driven from here by setting
// `ADFREE_CDP`, see the last describe block.

import { readFileSync } from 'node:fs';
import { chromium } from 'playwright';
import { afterAll, afterEach, beforeAll, describe, expect, test } from 'vitest';

const RUNTIME = readFileSync(new URL('./page_runtime.js', import.meta.url), 'utf8');

// The origin the tunnel answers itself: see docs/injection.md. Every endpoint
// sits under `/local`, and the runtime derives them from its own tag's
// directory, so this is also what the prefixes below are checked against.
const ORIGIN = 'https://yuuki-kagura.github.io/local';
const SITE = 'https://shop.test';
const PAGE_URL = `${SITE}/tin-tuc/`;

let browser = null;
const contexts = [];

beforeAll(async () => {
  // The system Chrome by default; `PLAYWRIGHT_CHANNEL=chromium` uses the
  // browser `npx playwright install chromium` fetched.
  browser = await chromium.launch({
    channel: process.env.PLAYWRIGHT_CHANNEL ?? 'chrome',
  });
});

afterEach(async () => {
  for (const context of contexts.splice(0)) {
    await context.close();
  }
});

afterAll(async () => {
  await browser?.close();
});

const page1 = (html) => `<!doctype html><html><head>${html.head ?? ''}</head><body>${html.body ?? ''}</body></html>`;

const hideLink = (query = 'dmn=shop.test&h=hide') =>
  `<link rel="stylesheet" id="adfree-hide" href="${ORIGIN}/adfree-hide.css?${query}">`;

// One page, both origins answered by routes: the site serves the document and
// the app's origin serves the runtime and the lookup answers, exactly the way
// the proxy would (including the CORS header the cross-origin fetch needs).
const openPage = async (options = {}) => {
  const context = await browser.newContext();
  contexts.push(context);
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  const lookups = [];
  const hides = [];
  const assistantCalls = [];

  await page.route(`${ORIGIN}/**`, async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname.replace(/^\/local/, '');
    if (path === '/adfree-page.js') {
      return route.fulfill({
        status: 200,
        contentType: 'application/javascript; charset=utf-8',
        body: RUNTIME,
      });
    }
    if (path === '/adfree-hide.css') {
      hides.push(url);
      return route.fulfill({
        status: 200,
        contentType: 'text/css; charset=utf-8',
        body: options.hideCss ?? '',
      });
    }
    if (path === '/adfree-assistant.json') {
      assistantCalls.push(url);
      const answer = typeof options.assistant === 'function'
        ? options.assistant(url)
        : (options.assistant ?? { ok: true });
      return route.fulfill({
        status: 200,
        contentType: 'application/json; charset=utf-8',
        headers: { 'access-control-allow-origin': '*' },
        body: JSON.stringify(answer),
      });
    }
    if (path === '/adfree-generic.js') {
      lookups.push(url);
      if (options.lookupFails) {
        return route.fulfill({ status: 500, contentType: 'text/plain', body: 'no' });
      }
      const answer = typeof options.answer === 'function' ? options.answer(url) : options.answer;
      return route.fulfill({
        status: 200,
        contentType: 'application/json; charset=utf-8',
        headers: { 'access-control-allow-origin': '*' },
        body: JSON.stringify(answer ?? []),
      });
    }
    return route.fulfill({ status: 404, contentType: 'text/plain', body: 'not the app’s' });
  });

  await page.route(`${SITE}/**`, async (route) => {
    const url = new URL(route.request().url());
    // The legacy shape: an injected tag served from the page's own origin.
    const path = url.pathname;
    if (path === '/adfree-page.js') {
      // A page cached before the channel moved: the tag is same-origin.
      return route.fulfill({
        status: 200,
        contentType: 'application/javascript; charset=utf-8',
        body: RUNTIME,
      });
    }
    if (path === '/adfree-generic.js') {
      // The runtime asks its own origin in that same legacy case.
      lookups.push(url);
      const answer = typeof options.answer === 'function' ? options.answer(url) : options.answer;
      return route.fulfill({
        status: 200,
        contentType: 'application/json; charset=utf-8',
        body: JSON.stringify(answer ?? []),
      });
    }
    if (route.request().resourceType() === 'document') {
      return route.fulfill({
        status: 200,
        contentType: 'text/html; charset=utf-8',
        headers: options.csp ? { 'content-security-policy': options.csp } : {},
        body: page1(options.html ?? {}),
      });
    }
    return route.fulfill({ status: 200, contentType: 'text/css; charset=utf-8', body: '' });
  });

  if (options.initScript) {
    await page.addInitScript(options.initScript);
  }
  await page.goto(PAGE_URL);
  if (options.before) {
    await options.before(page);
  }
  // The proxy writes the payload inline before the runtime tag, so the tests
  // do the same — unless the document already carries both (the CSP tests).
  if (options.scriptTag !== false) {
    await page.evaluate((payload) => {
      if (payload !== undefined) {
        window.__adfree_page = payload;
      }
    }, options.payload);
    await page.addScriptTag({
      url: options.scriptUrl ?? `${ORIGIN}/adfree-page.js?dmn=shop.test&h=test`,
    });
  }

  return {
    page,
    errors,
    lookups,
    hides,
    assistantCalls,
    display: (selector) =>
      page.evaluate((sel) => {
        const el = document.querySelector(sel);
        return el ? getComputedStyle(el).display : null;
      }, selector),
  };
};

test('the payload hides what it names and is not left behind', async () => {
  const page = await openPage({
    html: {
      head: hideLink(),
      body: '<div class="banner">banner</div><div class="ad-slot">slot</div>',
    },
    hideCss: '.banner{display:none!important}',
    payload: { generic: ['.ad-slot'] },
  });

  // The linker stylesheet the proxy writes, then the runtime's own rules.
  await expect.poll(() => page.display('.banner')).toBe('none');
  await expect.poll(() => page.display('.ad-slot')).toBe('none');

  expect(await page.page.evaluate(() => window.__adfree_page)).toBeUndefined();
  expect(page.errors).toEqual([]);
});

test('the page-local hide list replaces the host stylesheet', async () => {
  const page = await openPage({
    html: { body: '<div class="banner">banner</div><div class="ad-slot">slot</div>' },
    payload: { hide: ['.banner'], generic: ['.ad-slot'] },
  });

  await expect.poll(() => page.display('.banner')).toBe('none');
  await expect.poll(() => page.display('.ad-slot')).toBe('none');
  expect(page.hides).toEqual([]);
  expect(page.errors).toEqual([]);
});

test('the rules go into a constructable stylesheet, with no node to remove', async () => {
  const page = await openPage({
    payload: { generic: ['.banner'], style: [['.ad-slot', 'visibility:hidden']] },
    html: { body: '<div class="banner">banner</div><div class="ad-slot">slot</div>' },
  });

  expect(await page.page.evaluate(() => document.adoptedStyleSheets.length)).toBe(1);
  expect(await page.page.evaluate(() => document.querySelectorAll('style[data-adfree]').length)).toBe(0);
  expect(
    await page.page.evaluate(() =>
      [...document.adoptedStyleSheets[0].cssRules].map((rule) => rule.selectorText)
    )
  ).toEqual(['.banner', '.ad-slot']);
  expect(await page.page.evaluate(() => getComputedStyle(document.querySelector('.ad-slot')).visibility)).toBe('hidden');
  expect(page.errors).toEqual([]);
});

test('without constructable stylesheets a style element carries the rules, and survives removal', async () => {
  const page = await openPage({
    html: { body: '<div class="ad-slot">slot</div>' },
    payload: { generic: ['.ad-slot'] },
    initScript: () => {
      // The way an older WebView looks to the runtime.
      delete Document.prototype.adoptedStyleSheets;
      delete window.CSSStyleSheet;
    },
  });

  await expect.poll(() => page.display('.ad-slot')).toBe('none');
  expect(await page.page.evaluate(() => document.querySelectorAll('style[data-adfree]').length)).toBe(1);

  // A DOM sanitizer removing the node must not lose the hiding.
  await page.page.evaluate(() => document.querySelector('style[data-adfree]').remove());
  await expect
    .poll(() => page.page.evaluate(() => document.querySelectorAll('style[data-adfree]').length))
    .toBe(1);
  await expect.poll(() => page.display('.ad-slot')).toBe('none');
  expect(page.errors).toEqual([]);
});

test('a strict policy is satisfied by the nonce and the origin, carriers included', async () => {
  const nonce = 'csp-test-nonce-0123456789';
  const page = await openPage({
    // The shape the proxy writes for a nonce-locked page: the tags carry the
    // nonce, and our origin sits where the lookup's fetch lands. Nothing
    // without one of those gets through, so a rule that lands proves its
    // carrier was accepted by the browser's own policy check.
    // The origin carries a trailing slash, as the proxy writes it: a CSP source
    // without one matches its last path segment exactly and would allow
    // `/local` while refusing `/local/adfree-….js`.
    csp: `default-src 'none' ${ORIGIN}/; script-src 'nonce-${nonce}'; style-src 'nonce-${nonce}'`,
    // The document carries what the proxy injects, nonce included: the payload
    // inline, the stylesheet link, then the runtime tag — which is how
    // `currentScript` hands the runtime its own nonce.
    html: {
      head:
        `<script nonce="${nonce}">window.__adfree_page={"generic":[".ad-slot"],"scriptlets":"document.documentElement.setAttribute('data-ran','1')"};</script>` +
        `<link rel="stylesheet" id="adfree-hide" href="${ORIGIN}/adfree-hide.css?dmn=shop.test&h=hide" nonce="${nonce}">` +
        `<script nonce="${nonce}" src="${ORIGIN}/adfree-page.js?dmn=shop.test&h=test"></script>`,
      body: '<div class="banner">banner</div><div class="ad-slot">slot</div>',
    },
    hideCss: '.banner{display:none!important}',
    answer: ['.late-ad'],
    scriptTag: false,
    // The `<style>` element path is the one that has to carry the nonce.
    initScript: () => {
      delete Document.prototype.adoptedStyleSheets;
      delete window.CSSStyleSheet;
    },
  });

  // The link the proxy wrote: a nonce on `<link rel=stylesheet>` is honoured.
  await expect.poll(() => page.display('.banner')).toBe('none');
  // And the element the runtime builds itself: it read the nonce off its own
  // tag, or the browser would have dropped the sheet.
  await expect.poll(() => page.display('.ad-slot')).toBe('none');
  const style = await page.page.evaluate(() => {
    const el = document.querySelector('style[data-adfree]');
    return el ? { nonce: el.nonce, rules: el.sheet ? el.sheet.cssRules.length : 0 } : null;
  });
  expect(style).not.toBeNull();
  expect(style.nonce).toBe(nonce);
  expect(style.rules).toBe(1);

  // The lookup is a `fetch`, so it is the origin in `default-src` that keeps
  // it alive — a nonce does nothing for `connect-src`.
  await page.page.evaluate(() => {
    const el = document.createElement('div');
    el.className = 'late-ad';
    document.body.appendChild(el);
  });
  await expect.poll(() => page.lookups.length).toBe(1);
  await expect.poll(() => page.display('.late-ad')).toBe('none');

  // A scriptlet is inline JavaScript: `new Function` would be `'unsafe-eval'`
  // and dropped here, so it runs as a `<script>` carrying the same nonce.
  expect(
    await page.page.evaluate(() => document.documentElement.getAttribute('data-ran'))
  ).toBe('1');
  expect(page.errors).toEqual([]);
});

test('the hiding stylesheet link is re-attached when the page removes it', async () => {
  const page = await openPage({
    html: { head: hideLink(), body: '<div class="banner">banner</div>' },
    hideCss: '.banner{display:none!important}',
    payload: {},
  });

  await expect.poll(() => page.display('.banner')).toBe('none');
  await page.page.evaluate(() => document.getElementById('adfree-hide').remove());
  await expect
    .poll(() => page.page.evaluate(() => !!document.getElementById('adfree-hide')))
    .toBe(true);
  await expect.poll(() => page.display('.banner')).toBe('none');
  expect(page.errors).toEqual([]);
});

test('tokens the page adds later are looked up once, and the answer hides them', async () => {
  const page = await openPage({
    payload: {},
    answer: ['.sponsored'],
  });

  await page.page.evaluate(() => {
    const el = document.createElement('div');
    el.className = 'sponsored lazily-added';
    el.id = 'sponsored-1';
    el.textContent = 'sponsored';
    document.body.appendChild(el);
  });

  await expect.poll(() => page.lookups.length).toBe(1);
  const url = page.lookups[0];
  // The lookup goes back to the directory the runtime was served from: the
  // prefix is part of the base, so every endpoint keeps it.
  expect(`${url.origin}${url.pathname}`.startsWith(`${ORIGIN}/`)).toBe(true);
  expect(url.pathname).toBe('/local/adfree-generic.js');
  expect(url.searchParams.get('dmn')).toBe('shop.test');
  expect(url.searchParams.get('c')).toBe('sponsored lazily-added');
  expect(url.searchParams.get('i')).toBe('sponsored-1');
  await expect.poll(() => page.display('.sponsored')).toBe('none');

  // The same token is never asked about twice.
  await page.page.evaluate(() => {
    const el = document.createElement('div');
    el.className = 'sponsored';
    document.body.appendChild(el);
  });
  await page.page.waitForTimeout(700);
  expect(page.lookups.length).toBe(1);
  expect(page.errors).toEqual([]);
});

test('a page-scoped exception filters the dynamic generic answer', async () => {
  const page = await openPage({
    payload: { exceptions: ['.scope-excepted'] },
    answer: ['.scope-excepted', '.scope-hidden'],
  });

  await page.page.evaluate(() => {
    for (const className of ['scope-excepted', 'scope-hidden']) {
      const el = document.createElement('div');
      el.className = className;
      el.textContent = className;
      document.body.appendChild(el);
    }
  });

  await expect.poll(() => page.lookups.length).toBe(1);
  await expect.poll(() => page.display('.scope-hidden')).toBe('none');
  expect(await page.display('.scope-excepted')).not.toBe('none');
  expect(page.errors).toEqual([]);
});

test('$generichide keeps the runtime from asking anything', async () => {
  const page = await openPage({ payload: { dynamic: false } });
  await page.page.evaluate(() => {
    const el = document.createElement('div');
    el.className = 'sponsored';
    document.body.appendChild(el);
  });
  await page.page.waitForTimeout(700);
  expect(page.lookups.length).toBe(0);
  expect(page.errors).toEqual([]);
});

test('a lookup that fails is swallowed', async () => {
  const page = await openPage({ payload: {}, lookupFails: true });
  await page.page.evaluate(() => {
    const el = document.createElement('div');
    el.className = 'sponsored';
    document.body.appendChild(el);
  });
  await expect.poll(() => page.lookups.length).toBe(1);
  expect(await page.page.evaluate(() => document.adoptedStyleSheets.length)).toBe(0);
  expect(page.errors).toEqual([]);
});

test('scriptlets run while the runtime is injected', async () => {
  const page = await openPage({
    payload: { scriptlets: 'window.__adfree_scriptlet_ran = true;' },
  });
  expect(await page.page.evaluate(() => window.__adfree_scriptlet_ran)).toBe(true);
  expect(page.errors).toEqual([]);
});

test('raw JavaScript rules each run in a tag of their own', async () => {
  const page = await openPage({
    payload: {
      jsrules: [
        'window.__adfree_rule_one = 1;',
        'const = ;',
        'window.__adfree_rule_three = 3;',
      ],
    },
  });
  // The middle rule is a syntax error: a tag would be dropped wholesale, so
  // the rules around it must still have run.
  expect(await page.page.evaluate(() => window.__adfree_rule_one)).toBe(1);
  expect(await page.page.evaluate(() => window.__adfree_rule_three)).toBe(3);
});

test('a payload of the wrong shape does not take the page down', async () => {
  const page = await openPage({
    html: { body: '<div class="ad-slot">slot</div>' },
    payload: {
      generic: 'not a list',
      style: 42,
      procedural: [{ selector: [{ type: 'css-selector', arg: '>>>' }], action: { type: 'remove' } }],
      scriptlets: 'throw new Error("this scriptlet is broken on purpose");',
    },
    answer: 'not a list either',
  });

  await page.page.evaluate(() => {
    const el = document.createElement('div');
    el.className = 'late';
    document.body.appendChild(el);
  });
  await expect.poll(() => page.lookups.length).toBe(1);
  expect(await page.page.evaluate(() => document.querySelector('.ad-slot') !== null)).toBe(true);
  expect(page.errors).toEqual([]);
});

describe('procedural rules', () => {
  test('a rule can remove an element it reaches through an upward step', async () => {
    const page = await openPage({
      html: {
        body: '<div class="ad-wrapper"><span class="ad-child">Quảng cáo</span></div><div id="keep">Nội dung</div>',
      },
      payload: {
        procedural: [
          // The engine writes these as JSON strings; both shapes must work.
          JSON.stringify({
            selector: [
              { type: 'css-selector', arg: '.ad-child' },
              { type: 'upward', arg: '.ad-wrapper' },
            ],
            action: { type: 'remove' },
          }),
        ],
      },
    });
    await expect.poll(() => page.page.evaluate(() => document.querySelectorAll('.ad-wrapper').length)).toBe(0);
    expect(await page.page.evaluate(() => document.getElementById('keep') !== null)).toBe(true);
    expect(page.errors).toEqual([]);
  });

  test('has-text and matches-attr narrow the match', async () => {
    const page = await openPage({
      html: {
        body:
          '<div class="box" data-ad="1">Quảng cáo</div>' +
          '<div class="box" data-ad="1">Nội dung</div>' +
          '<div class="box" data-ad="0">Quảng cáo</div>',
      },
      payload: {
        procedural: [
          {
            selector: [
              { type: 'css-selector', arg: '.box' },
              { type: 'has-text', arg: 'Quảng cáo' },
              { type: 'matches-attr', arg: 'data-ad=1' },
            ],
            action: { type: 'remove-class', arg: 'box' },
          },
        ],
      },
    });
    await expect.poll(() => page.page.evaluate(() => document.querySelectorAll('.box').length)).toBe(2);
    expect(await page.page.evaluate(() => document.body.textContent)).toBe('Quảng cáoNội dungQuảng cáo');
    expect(page.errors).toEqual([]);
  });

  test('a :has() step with a procedural argument matches a descendant, not the text', async () => {
    const page = await openPage({
      html: {
        body:
          '<div class="text-center"><small>QUẢNG CÁO</small></div>' +
          '<div class="text-center"><small>Nội dung</small></div>' +
          '<div class="text-center">QUẢNG CÁO</div>',
      },
      payload: {
        procedural: [
          // `.text-center:-abp-has(small:-abp-contains(QUẢNG CÁO))`, the rule
          // abpvn carries for metruyencv.com. The text has to be inside the
          // `small`, so the third box must survive.
          {
            selector: [
              { type: 'css-selector', arg: '.text-center' },
              {
                type: 'has',
                arg: [
                  { type: 'css-selector', arg: 'small' },
                  { type: 'has-text', arg: 'QUẢNG CÁO' },
                ],
              },
            ],
            action: { type: 'remove' },
          },
        ],
      },
    });
    await expect.poll(() => page.page.evaluate(() => document.querySelectorAll('.text-center').length)).toBe(2);
    expect(
      await page.page.evaluate(() =>
        Array.from(document.querySelectorAll('.text-center')).map((el) => el.textContent)
      )
    ).toEqual(['Nội dung', 'QUẢNG CÁO']);
    expect(page.errors).toEqual([]);
  });

  test('a :has() argument with a leading combinator stays inside the element', async () => {
    const page = await openPage({
      html: {
        body:
          '<div id="outer"><div class="slot">a</div></div>' +
          '<div id="other"><div class="wrap"><div class="slot">b</div></div></div>',
      },
      payload: {
        procedural: [
          {
            selector: [
              { type: 'css-selector', arg: '#outer, #other' },
              { type: 'has', arg: [{ type: 'css-selector', arg: '> .slot' }] },
            ],
            action: { type: 'remove' },
          },
        ],
      },
    });
    // Only #outer has `.slot` as a direct child.
    await expect.poll(() => page.page.evaluate(() => document.getElementById('outer') === null)).toBe(true);
    expect(await page.page.evaluate(() => document.getElementById('other') !== null)).toBe(true);
    expect(page.errors).toEqual([]);
  });

  test('watch-attr runs the rule again when the attribute changes', async () => {
    const page = await openPage({
      html: {
        body: '<div class="box" data-ad="no">Quảng cáo</div><div class="box" data-ad="yes">Nội dung</div>',
      },
      payload: {
        procedural: [
          {
            selector: [
              { type: 'css-selector', arg: '.box' },
              { type: 'matches-attr', arg: 'data-ad=yes' },
              // uBO's operator: the rule is re-run when this attribute moves,
              // which is how a page that flips an attribute to reveal its ad
              // gets covered after the first pass missed it.
              { type: 'watch-attr', arg: 'data-ad' },
            ],
            action: { type: 'remove' },
          },
        ],
      },
    });
    // The first pass sees one match and leaves the other box alone.
    await expect.poll(() => page.page.evaluate(() => document.querySelectorAll('.box').length)).toBe(1);
    // An attribute-only change is not a child-list change: nothing but the
    // watcher can notice it.
    await page.page.evaluate(() => document.querySelector('.box').setAttribute('data-ad', 'yes'));
    await expect.poll(() => page.page.evaluate(() => document.querySelectorAll('.box').length)).toBe(0);
    expect(page.errors).toEqual([]);
  });

  test('watch-attr does not narrow what the chain already matched', async () => {
    const page = await openPage({
      html: { body: '<div class="ad">one</div><div class="keep">two</div>' },
      payload: {
        procedural: [
          {
            selector: [
              { type: 'css-selector', arg: '.ad' },
              { type: 'watch-attr', arg: 'class' },
            ],
            action: { type: 'remove-class', arg: 'ad' },
          },
        ],
      },
    });
    await expect.poll(() => page.page.evaluate(() => document.querySelectorAll('.ad').length)).toBe(0);
    expect(await page.page.evaluate(() => document.querySelectorAll('.keep').length)).toBe(1);
    expect(page.errors).toEqual([]);
  });

  test('matches-prop reads a property path, regex segment and all', async () => {
    const page = await openPage({
      html: {
        body:
          '<img id="wide" src="a.png"><img id="tall" src="b.png">' +
          '<div id="react"></div>',
      },
      payload: {
        procedural: [
          {
            selector: [
              { type: 'css-selector', arg: 'img' },
              // `naturalWidth` is a property, never an attribute.
              { type: 'matches-prop', arg: 'naturalWidth=2' },
            ],
            action: { type: 'remove' },
          },
          {
            selector: [
              { type: 'css-selector', arg: '#react' },
              // A segment written as `/re/` names a property of the object,
              // which is how the lists reach into minified React internals.
              { type: 'matches-prop', arg: '"/__reactFiber/.memoizedProps.placeholder"="true"' },
            ],
            action: { type: 'remove' },
          },
        ],
      },
    });
    await page.page.evaluate(() => {
      // 1x1 PNGs: one 2 px wide, one 3 px wide.
      const png = (w) =>
        'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==';
      void png;
      const wide = document.getElementById('wide');
      Object.defineProperty(wide, 'naturalWidth', { value: 2 });
      Object.defineProperty(document.getElementById('tall'), 'naturalWidth', { value: 3 });
      const react = document.getElementById('react');
      react.__reactFiber$abc = { memoizedProps: { placeholder: 'true' } };
      // A property set on an element is not a DOM mutation, so the rules only
      // see it once something does change.
      document.body.appendChild(document.createElement('span'));
    });
    await expect.poll(() => page.page.evaluate(() => document.getElementById('wide') === null)).toBe(true);
    expect(await page.page.evaluate(() => document.getElementById('tall') !== null)).toBe(true);
    await expect.poll(() => page.page.evaluate(() => document.getElementById('react') === null)).toBe(true);
    expect(page.errors).toEqual([]);
  });

  test('matches-media applies the rule only when the query holds', async () => {
    const page = await openPage({
      html: { body: '<div class="wide">a</div><div class="narrow">b</div>' },
      payload: {
        procedural: [
          {
            selector: [
              { type: 'css-selector', arg: '.wide' },
              { type: 'matches-media', arg: '(min-width: 800px)' },
            ],
            action: { type: 'remove' },
          },
          {
            selector: [
              { type: 'css-selector', arg: '.narrow' },
              { type: 'matches-media', arg: '(max-width: 100px)' },
            ],
            action: { type: 'remove' },
          },
        ],
      },
    });
    await page.page.waitForTimeout(300);
    const kept = await page.page.evaluate(() => ({
      wide: document.querySelectorAll('.wide').length,
      narrow: document.querySelectorAll('.narrow').length,
    }));
    // The test window is wider than 800 px and wider than 100 px.
    expect(kept).toEqual({ wide: 0, narrow: 1 });
    expect(page.errors).toEqual([]);
  });

  test('an xpath step works', async () => {
    const page = await openPage({
      html: { body: '<div class="ad-slot">one</div><div class="ad-slot">two</div>' },
      payload: {
        procedural: [
          {
            selector: [{ type: 'xpath', arg: '//div[@class="ad-slot"][1]' }],
            action: { type: 'remove' },
          },
        ],
      },
    });
    await expect.poll(() => page.page.evaluate(() => document.querySelectorAll('.ad-slot').length)).toBe(1);
    expect(await page.page.evaluate(() => document.querySelector('.ad-slot').textContent)).toBe('two');
    expect(page.errors).toEqual([]);
  });

  test('matches-path skips the rule on another path', async () => {
    const page = await openPage({
      html: { body: '<div class="ad-slot">slot</div>' },
      payload: {
        procedural: [
          {
            selector: [
              { type: 'matches-path', arg: '^/khac/' },
              { type: 'css-selector', arg: '.ad-slot' },
            ],
            action: { type: 'remove' },
          },
        ],
      },
    });
    await page.page.waitForTimeout(300);
    expect(await page.page.evaluate(() => document.querySelectorAll('.ad-slot').length)).toBe(1);
    expect(page.errors).toEqual([]);
  });

  test('a rule added to the DOM later is applied', async () => {
    const page = await openPage({
      payload: {
        procedural: [{ selector: [{ type: 'css-selector', arg: '.late-ad' }], action: { type: 'remove' } }],
      },
    });
    await page.page.evaluate(() => {
      const el = document.createElement('div');
      el.className = 'late-ad';
      document.body.appendChild(el);
    });
    await expect.poll(() => page.page.evaluate(() => document.querySelectorAll('.late-ad').length)).toBe(0);
    expect(page.errors).toEqual([]);
  });
});

test('a legacy same-origin tag still finds the host and origin', async () => {
  const page = await openPage({
    payload: {},
    scriptUrl: `${SITE}/adfree-page.js?h=old`,
    answer: ['.sponsored'],
  });
  await page.page.evaluate(() => {
    const el = document.createElement('div');
    el.className = 'sponsored';
    document.body.appendChild(el);
  });
  await expect.poll(() => page.lookups.length).toBe(1);
  const url = page.lookups[0];
  expect(url.origin).toBe(SITE);
  expect(url.searchParams.get('dmn')).toBe('shop.test');
  await expect.poll(() => page.display('.sponsored')).toBe('none');
  expect(page.errors).toEqual([]);
});

// A blocked ad leaves its box behind. These pin the pass that collapses it:
// what counts as a slot, what counts as empty, and how far up the box goes.
describe('emptied ad slots', () => {
  /// The leftover on click2houston.com: the wrapper reserves 250 px and the
  /// ad inside it is named `fixedAd`, a name no attribute selector covers.
  test('a slot named only by its class is collapsed, wrapper and all', async () => {
    const page = await openPage({
      html: {
        head: '<style>#band{height:250px;background:#333}</style>',
        body:
          '<div id="band"><div id="MileTech_LEADER_BOARD1_DESKTOP" class="fixedAd fixedAd-rendered"></div></div>' +
          '<main style="height:800px">story</main>',
      },
    });

    await expect.poll(() => page.display('#band')).toBe('none');
    expect(await page.display('main')).not.toBe('none');
    expect(page.errors).toEqual([]);
  });

  /// The other half of the same page: a sticky frame at the bottom of the
  /// viewport with its own close button. The button is a control, not content,
  /// and goes with the frame — otherwise the frame keeps covering the story.
  /// Its icon is drawn with an `<svg>`, and a picture inside a control is still
  /// a control: the first cut of this pass read it as content and left the
  /// 90 px frame on screen.
  test('a close button does not keep an emptied frame on screen', async () => {
    const page = await openPage({
      html: {
        head:
          '<style>.floatingWrapper{position:fixed;bottom:0;left:0;right:0;height:90px;background:#222}</style>',
        body:
          '<div class="floatingWrapper">' +
          '<button class="close" style="width:48px;height:32px"><span><div class="buttonIcon">' +
          '<svg aria-hidden="true" viewBox="0 0 512 512" style="width:16px;height:16px"></svg>' +
          '</div></span>Close</button>' +
          '<div id="MileTech_FOOTER_DESKTOP" class="fixedAd"></div></div>',
      },
    });

    await expect.poll(() => page.display('.floatingWrapper')).toBe('none');
    expect(page.errors).toEqual([]);
  });

  /// Google names the AI answer panel `D5ad8b` and the box beside it
  /// `GkDqAd`. Split into words both carry an ad: `ad` sits between two digits
  /// in the first and on its own at the end of the second. The panel ships
  /// empty and is filled a moment later, so collapsing it hid the whole answer
  /// behind a 383 px black band — the reported gemini search preview.
  test('a hash-shaped name is not an ad slot', async () => {
    const page = await openPage({
      html: {
        head: '<style>#panel{height:383px;background:#000}</style>',
        body: '<div id="panel" class="D5ad8b"><div class="GkDqAd"></div></div>',
      },
    });

    // The observer pass runs on mutations; give it the same window the real
    // page has before the answer arrives.
    await page.page.waitForTimeout(1200);
    expect(await page.display('#panel')).not.toBe('none');
    expect(page.errors).toEqual([]);
  });

  /// A real slot still collapses, and a panel that was genuinely empty when
  /// the pass ran gets its box back when the page fills it — the other half of
  /// the same report: hiding a box that later receives content hides the
  /// content.
  test('a slot that fills later gets its box back', async () => {
    const page = await openPage({
      html: {
        head: '<style>#late{height:120px;background:#111}</style>',
        body: '<div id="late" class="ad-slot"></div>',
      },
    });

    await expect.poll(() => page.display('#late')).toBe('none');
    await page.page.evaluate(() => {
      document.querySelector('#late').textContent =
        'Vite bundles with Rollup and serves with esbuild; Vike is a framework on top of Vite.';
    });
    await expect.poll(() => page.display('#late')).not.toBe('none');
    expect(page.errors).toEqual([]);
  });

  /// The walk has to stop at content: a picture in the wrapper is the story,
  /// not the ad, so the wrapper keeps its box even though the slot collapses.
  test('a wrapper holding a picture keeps its box', async () => {
    const page = await openPage({
      html: {
        body:
          '<div id="keep"><div class="ad-slot"></div>' +
          '<img src="data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7" width="120" height="60"></div>',
      },
    });

    await expect.poll(() => page.display('.ad-slot')).toBe('none');
    expect(await page.display('#keep')).not.toBe('none');
    expect(page.errors).toEqual([]);
  });

  /// `Advertisement` is the page telling the reader an ad would be here; a
  /// sentence is not, and a slot carrying one is left alone rather than
  /// guessed at.
  test('only the ad label counts as nothing in a slot', async () => {
    const page = await openPage({
      html: {
        head: '<style>.ad-slot{height:120px}</style>',
        body:
          '<div class="ad-slot" id="label">Advertisement</div>' +
          '<div class="ad-slot" id="promo">Bán hàng giảm giá hôm nay</div>',
      },
    });

    await expect.poll(() => page.display('#label')).toBe('none');
    expect(await page.display('#promo')).not.toBe('none');
    expect(page.errors).toEqual([]);
  });
});

// The one check that is not simulated: a Chrome running on the emulator with
// the app's VPN up, driven over CDP. Off unless ADFREE_CDP is set, e.g.
//
//   adb forward tcp:9333 localabstract:chrome_devtools_remote
//   ADFREE_CDP=http://localhost:9333 bun run test
describe.skipIf(!process.env.ADFREE_CDP)('behind the running VPN', () => {
  test('a real page carries the injected tags and the runtime answers', async () => {
    const connected = await chromium.connectOverCDP(process.env.ADFREE_CDP);
    const context = connected.contexts()[0];
    const page = await context.newPage();

    // Fresh bytes: a cached copy of the page from an earlier run carries no
    // tags, and this test is about what the running VPN does to a response.
    const cdp = await context.newCDPSession(page);
    await cdp.send('Network.enable');
    await cdp.send('Network.setCacheDisabled', { cacheDisabled: true }).catch(() => {});
    await cdp.send('Network.clearBrowserCache').catch(() => {});

    const url = process.env.ADFREE_PAGE ?? 'https://news.ycombinator.com/';
    const load = async () => {
      // `domcontentloaded` plus a swallowed timeout: a news page keeps loading
      // ads for a minute, and the document is what this test reads.
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 }).catch(() => {});
      await page.waitForTimeout(2500);
      return page.evaluate(() =>
        [...document.querySelectorAll('script[src], link[href]')]
          .map((el) => el.src || el.href)
          .filter((u) => u.includes('/local/'))
      );
    };

    // The relay occasionally lets a connection through untouched — the app
    // logs nothing for it, so it is not an injection failure (see the open
    // issue in docs/rust-owned-state.md). A few fresh loads absorb that; a
    // page the injector really missed still fails the assertions below.
    let tags = [];
    for (let attempt = 0; attempt < 3; attempt++) {
      tags = await load();
      if (
        tags.some((u) => u.includes('/adfree-page.js')) &&
        tags.some((u) => u.includes('/adfree-hide.css'))
      ) {
        break;
      }
    }
    expect(tags.some((url) => url.includes('/adfree-page.js'))).toBe(true);
    expect(tags.some((url) => url.includes('/adfree-hide.css'))).toBe(true);

    // The lookup is the one endpoint a page may read cross-origin: the answer
    // is JSON, and asking for it also proves the certificate for the injection
    // host was accepted including the CORS header.
    const host = new URL(url).host;
    const answer = await page.evaluate(
      (dmn) =>
        fetch(`https://yuuki-kagura.github.io/local/adfree-generic.js?dmn=${dmn}&c=adfree-canary&i=adfree-canary`, {
          cache: 'no-store',
        }).then((r) => r.json()),
      host
    );
    expect(Array.isArray(answer)).toBe(true);

    // The runtime must have *run*, not just loaded: its own lookup request is
    // in the page's resource timeline. (The runtime's source itself is not
    // readable from the page — that is the point of the CORS narrowing.)
    const asked = await page.evaluate(() =>
      performance.getEntriesByType('resource').some((e) => e.name.includes('/adfree-generic.js'))
    );
    expect(asked).toBe(true);
    await page.close().catch(() => {});
    await connected.close();
  });
});

describe('the AdGuard Assistant bridge', () => {
  /// The assistant published by AdGuard calls these five globals on the page.
  /// Nothing in a plain browser defines them, so their presence *is* the
  /// compatibility surface: with them installed the bundle runs unmodified.
  test('the five globals the assistant calls are installed', async () => {
    const page = await openPage({ payload: { generic: [] } });
    const types = await page.page.evaluate(() =>
      [
        'ADG_addRule',
        'ADG_temporaryDontBlock',
        'ADG_isFiltered',
        'ADG_changeFilteringState',
        'ADG_sendAbuse',
      ]
        .map((name) => typeof window[name])
        .join(','),
    );
    expect(types).toBe('function,function,function,function,function');
    expect(page.errors).toEqual([]);
  });

  /// A rule the user built on the page is an app rule: it has to reach the
  /// engine, and the assistant waits for the answer before it closes.
  test('a rule the assistant builds goes to the app and is acknowledged', async () => {
    const page = await openPage({
      payload: { generic: [] },
      assistant: { ok: true, added: true },
    });
    const answered = await page.page.evaluate(
      () => new Promise((resolve) => window.ADG_addRule('shop.test##.banner', resolve)),
    );
    expect(answered).toBe(true);
    await expect.poll(() => page.assistantCalls.length).toBe(1);
    const url = page.assistantCalls[0];
    expect(url.pathname).toBe('/local/adfree-assistant.json');
    expect(url.searchParams.get('act')).toBe('rule');
    expect(url.searchParams.get('rule')).toBe('shop.test##.banner');
    expect(url.searchParams.get('dmn')).toBe('shop.test');
    expect(page.errors).toEqual([]);
  });

  /// "Temporarily don't block this page" is an exception the app adds and
  /// later takes back, so both halves of the round trip have to be there.
  test('the temporary whitelist adds an exception and takes it back', async () => {
    const page = await openPage({
      payload: { generic: [] },
      assistant: { ok: true },
      // Granting a document exception only takes effect on the next load, so
      // the glue reloads the page; losing the document would lose the timer
      // under test.
      initScript: 'window.__adfreeNoAssistantReload = true;',
    });
    // A one-second window (the shortest the glue honours): the exception is
    // added, then taken back, so the poll waits past that second.
    await page.page.evaluate(() => window.ADG_temporaryDontBlock(1, () => {}));
    await expect
      .poll(() => page.assistantCalls.length, { timeout: 5000, interval: 100 })
      .toBe(2);
    expect(page.assistantCalls[0].searchParams.get('act')).toBe('rule');
    expect(page.assistantCalls[0].searchParams.get('rule')).toBe('@@||shop.test^$document');
    expect(page.assistantCalls[1].searchParams.get('act')).toBe('unrule');
    expect(page.assistantCalls[1].searchParams.get('rule')).toBe('@@||shop.test^$document');
    expect(page.errors).toEqual([]);
  });

  /// The panel's own switch: the app owns the tunnel, so the request is handed
  /// over rather than performed here.
  test('the panel switch and the filtered question both reach the app', async () => {
    const page = await openPage({
      payload: { generic: [] },
      assistant: (url) =>
        url.searchParams.get('act') === 'filtered' ? { ok: true, filtered: true } : { ok: true },
    });
    const filtered = await page.page.evaluate(
      () => new Promise((resolve) => window.ADG_isFiltered(resolve)),
    );
    expect(filtered).toBe(true);
    await page.page.evaluate(() => new Promise((resolve) => window.ADG_changeFilteringState(false, resolve)));
    await expect.poll(() => page.assistantCalls.length).toBe(2);
    expect(page.assistantCalls[0].searchParams.get('act')).toBe('filtered');
    expect(page.assistantCalls[1].searchParams.get('act')).toBe('switch');
    expect(page.assistantCalls[1].searchParams.get('on')).toBe('0');
    expect(page.errors).toEqual([]);
  });
});
