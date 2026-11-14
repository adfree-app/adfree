/*
 * The store button.
 *
 * Injected into a script-repository page by the proxy, next to the site's own
 * "Install this script" link, so installing through AdFree looks like using the
 * page rather than the app. The page keeps working if this script never runs:
 * it only ever adds a control.
 *
 * Contract with the app: `window.__adfree_store` carries the endpoint, the
 * per-run token and the list of scripts already installed. Nothing here talks to
 * the network until someone clicks, and the token is only ever sent to
 * 127.0.0.1.
 */
(() => {
  "use strict";

  const state = window.__adfree_store;
  if (!state || !state.api) {
    return;
  }

  const STRINGS = {
    vi: {
      install: "Cài đặt bằng AdFree",
      update: "Cập nhật",
      remove: "Gỡ cài đặt",
      installing: "Đang cài đặt…",
      removing: "Đang gỡ…",
      installed: "Đã cài đặt",
      version: "v",
      failed: "Không cài được",
    },
    en: {
      install: "Install with AdFree",
      update: "Update",
      remove: "Uninstall",
      installing: "Installing…",
      removing: "Removing…",
      installed: "Installed",
      version: "v",
      failed: "Could not install",
    },
  };

  const lang = (document.documentElement.lang || navigator.language || "en").slice(0, 2).toLowerCase();
  const t = STRINGS[lang] || STRINGS.en;

  /* The one identity Greasy Fork never changes: the numeric script id. An
     update can rename the file, and the slug in the URL follows the name. */
  function keyFor(url) {
    const flick = /update\.greasyfork\.org\/scripts\/(\d+)/.exec(url) ||
      /greasyfork\.org\/scripts\/(\d+)/.exec(url);
    if (flick) return "greasyfork:" + flick[1];
    return String(url || "").split(/[?#]/)[0];
  }

  function installedEntry(url) {
    const key = keyFor(url);
    return (state.installed || []).find((row) => row.key === key) || null;
  }

  function button(className, label, title) {
    const node = document.createElement("a");
    node.className = className;
    node.href = "#";
    node.textContent = label;
    if (title) node.title = title;
    node.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
    });
    return node;
  }

  async function call(path, body) {
    const response = await fetch(state.api + path, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(Object.assign({ token: state.token }, body)),
    });
    return response.json();
  }

  function render(area, url, pageVersion) {
    const existing = document.getElementById("adfree-store-button");
    if (existing) existing.remove();
    const entry = installedEntry(url);
    const actions = document.getElementById("adfree-store-uninstall");
    if (actions) actions.remove();

    const install = button("install-link adfree-store-button", "", "");
    install.id = "adfree-store-button";

    if (!entry) {
      install.textContent = t.install;
      install.addEventListener("click", () => run(install, area, url, pageVersion));
    } else if (pageVersion && entry.version && entry.version !== pageVersion) {
      install.textContent = t.update + " " + t.version + pageVersion;
      install.addEventListener("click", () => run(install, area, url, pageVersion));
      addUninstall(area, url, pageVersion);
    } else {
      install.textContent = "✓ " + t.installed;
      install.classList.add("adfree-store-installed");
      install.addEventListener("click", (event) => event.preventDefault());
      addUninstall(area, url, pageVersion);
    }
    return install;
  }

  function addUninstall(area, url, pageVersion) {
    const remove = button("install-help-link adfree-store-uninstall", "🗑", t.remove);
    remove.id = "adfree-store-uninstall";
    remove.addEventListener("click", async () => {
      remove.textContent = "…";
      try {
        const answer = await call("/uninstall", { url });
        if (!answer.ok) throw new Error(answer.error || "");
        state.installed = (state.installed || []).filter((row) => row.key !== keyFor(url));
        paint(area, url, pageVersion);
      } catch (error) {
        remove.textContent = "🗑";
        report(area, t.failed);
      }
    });
    area.appendChild(remove);
  }

  function report(area, message) {
    const note = document.getElementById("adfree-store-note");
    if (note) note.remove();
    const span = document.createElement("span");
    span.id = "adfree-store-note";
    span.className = "adfree-store-note";
    span.textContent = " " + message;
    area.appendChild(span);
    window.setTimeout(() => span.remove(), 6000);
  }

  async function run(node, area, url, pageVersion) {
    const label = node.textContent;
    node.textContent = t.installing;
    node.style.pointerEvents = "none";
    try {
      const answer = await call("/install", { url });
      if (!answer.ok) throw new Error(answer.error || "");
      state.installed = (state.installed || []).filter((row) => row.key !== keyFor(url));
      state.installed.push({
        id: answer.id,
        name: answer.name,
        url,
        key: keyFor(url),
        version: answer.version || pageVersion,
      });
      paint(area, url, pageVersion);
    } catch (error) {
      node.textContent = label;
      node.style.pointerEvents = "";
      report(area, t.failed + " (" + (error && error.message ? error.message : "?") + ")");
    }
  }

  function paint(area, url, pageVersion) {
    const link = area.querySelector("a.install-link:not(.adfree-store-button)");
    const node = render(area, url, pageVersion);
    // Right after the site's own link, before the "?" help icon: the pair reads
    // as one control group, which is what a store page does.
    if (link && link.nextSibling) {
      area.insertBefore(node, link.nextSibling);
    } else {
      area.appendChild(node);
    }
  }

  function attach() {
    const area = document.getElementById("install-area");
    const link = area && area.querySelector("a.install-link:not(.adfree-store-button)");
    if (!area || !link) return false;
    const url = link.getAttribute("href") || "";
    if (!url) return false;
    const pageVersion = link.getAttribute("data-script-version") || "";
    paint(area, url, pageVersion);
    return true;
  }

  if (!attach()) {
    // This tag is written at the end of `<head>`, so the install area is not in
    // the document yet when the script runs: the wait belongs here, not in the
    // guard above — giving up on a page that will render the area a moment
    // later is exactly how the button never reached Greasy Fork.
    //
    // The observer stays until the deadline rather than disconnecting on the
    // first success: a store page may repaint its install area (a version
    // banner replaces it), which takes our node with it.
    const observer = new MutationObserver(() => {
      if (document.getElementById("adfree-store-button")) return;
      attach();
    });
    observer.observe(document.documentElement, { childList: true, subtree: true });
    window.setTimeout(() => observer.disconnect(), 15000);
  }
})();
