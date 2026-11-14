//! Stealth-mode helpers: the request bits AdGuard hides from sites.
//!
//! Everything here is pure so the rules can be tested without a socket:
//! tracking and search-query parameters, third-party classification, STUN
//! detection, the fixed user agent the disguise replaces the client's own with,
//! and the browser-API guards a page is given before its own scripts run.

use crate::filter::url_host;

/// The user agent a disguised request carries.
///
/// Chrome's own reduced Android UA: it hides the device model and the exact
/// version while still saying "a mobile browser", so sites keep serving the
/// layout the user actually expects.
pub const DISGUISED_USER_AGENT: &str = "Mozilla/5.0 (Linux; Android 10; K) \
AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

/// The scriptlet injected into pages when WebRTC blocking is on.
///
/// Packet-level drops only stop STUN/TURN traffic; a page can still learn the
/// device's private address from the `host` ICE candidates the browser puts in
/// its own SDP. This guard removes those candidates before they reach the
/// page, for both `setLocalDescription` and the `icecandidate` events.
pub const WEBRTC_GUARD: &str = r#"
(() => {
  'use strict';
  const RTC = window.RTCPeerConnection;
  if (!RTC || !RTC.prototype) return;
  const proto = RTC.prototype;
  const isHost = (line) =>
    typeof line === 'string' &&
    line.indexOf('a=candidate:') === 0 &&
    / typ host(\s|$)/.test(line);
  const cleanSdp = (sdp) => {
    if (typeof sdp !== 'string') return sdp;
    const sep = sdp.indexOf('\r\n') >= 0 ? '\r\n' : '\n';
    return sdp.split(/\r\n|\n/).filter((line) => !isHost(line)).join(sep);
  };
  const setLocal = proto.setLocalDescription;
  if (setLocal) {
    proto.setLocalDescription = function (description, ...rest) {
      try {
        if (description && typeof description === 'object' && typeof description.sdp === 'string') {
          description = { ...description, sdp: cleanSdp(description.sdp) };
        }
      } catch { /* leave it alone */ }
      return setLocal.call(this, description, ...rest);
    };
  }
  const dropHost = (event) => {
    try {
      const candidate = event && event.candidate && event.candidate.candidate;
      return isHost(candidate) || / typ host(\s|$)/.test(candidate || '');
    } catch { return false; }
  };
  const add = proto.addEventListener;
  if (add) {
    proto.addEventListener = function (type, listener, ...rest) {
      if (type === 'icecandidate' && typeof listener === 'function') {
        const wrapped = function (event) {
          if (dropHost(event)) return;
          return listener.call(this, event);
        };
        return add.call(this, type, wrapped, ...rest);
      }
      return add.call(this, type, listener, ...rest);
    };
  }
  const onIce = Object.getOwnPropertyDescriptor(proto, 'onicecandidate');
  if (onIce && onIce.set && onIce.get) {
    Object.defineProperty(proto, 'onicecandidate', {
      configurable: true,
      enumerable: onIce.enumerable,
      get: onIce.get,
      set: function (fn) {
        if (typeof fn !== 'function') return onIce.set.call(this, fn);
        return onIce.set.call(this, function (event) {
          if (dropHost(event)) return;
          return fn.call(this, event);
        });
      },
    });
  }
})();
"#;

/// The scriptlet injected into pages when the Push API is blocked.
///
/// A subscription needs three things: a service worker, a permission the user
/// grants, and `PushManager.subscribe`. Denying any one of them is enough to
/// stop the subscription, so all three are closed here — the register call
/// rejects, the permission reads `denied` and never prompts, and `subscribe`
/// rejects. A page that only wants notifications sees the same answer it would
/// have seen after the user tapped "block".
pub const PUSH_GUARD: &str = r#"
(() => {
  'use strict';
  const deny = () => Promise.reject(new DOMException('Blocked by AdFree', 'NotAllowedError'));
  try {
    const push = window.PushManager && window.PushManager.prototype;
    if (push) {
      push.subscribe = deny;
      push.getSubscription = () => Promise.resolve(null);
    }
    if (window.Notification) {
      Object.defineProperty(window.Notification, 'permission', {
        configurable: true,
        get: () => 'denied',
      });
      window.Notification.requestPermission = () => Promise.resolve('denied');
    }
    const container = navigator.serviceWorker && Object.getPrototypeOf(navigator.serviceWorker);
    if (container) {
      container.register = deny;
    }
  } catch { /* a frozen prototype: nothing more to close */ }
})();
"#;

/// The scriptlet injected into pages when the Geolocation API is blocked.
///
/// `GeolocationPositionError` cannot be constructed by a page, so the error
/// handed to the callback is an object shaped like one: every site that reads
/// `code` or `message` sees `PERMISSION_DENIED` exactly as it would after the
/// user turned the prompt down. `watchPosition` reports the failure once and
/// never fires again.
pub const GEOLOCATION_GUARD: &str = r#"
(() => {
  'use strict';
  try {
    const geo = navigator.geolocation;
    if (!geo) return;
    const proto = Object.getPrototypeOf(geo);
    if (!proto) return;
    const denial = () => ({
      code: 1,
      message: 'User denied Geolocation',
      PERMISSION_DENIED: 1,
      POSITION_UNAVAILABLE: 2,
      TIMEOUT: 3,
      time: null,
      coords: null,
    });
    const fail = (error) => {
      setTimeout(() => {
        if (typeof error === 'function') {
          try { error(denial()); } catch { /* the page's own callback */ }
        }
      }, 0);
    };
    proto.getCurrentPosition = function (_success, error) { fail(error); };
    proto.watchPosition = function (_success, error) {
      fail(error);
      return 0;
    };
    proto.clearWatch = function () {};
    const permissions = navigator.permissions;
    if (permissions) {
      const query = Object.getPrototypeOf(permissions).query;
      if (query) {
        Object.getPrototypeOf(permissions).query = function (description) {
          if (description && description.name === 'geolocation') {
            return Promise.resolve({
              state: 'denied',
              name: 'geolocation',
              onchange: null,
              addEventListener() {},
              removeEventListener() {},
            });
          }
          return query.call(this, description);
        };
      }
    }
  } catch { /* a frozen prototype: nothing more to close */ }
})();
"#;

/// Query parameters whose only job is to name the campaign that sent a
/// visitor. `utm_*` is matched by prefix; the rest are the one-off click ids
/// the big ad networks add.
const TRACKING_PARAMS: &[&str] = &[
    "fbclid",
    "gclid",
    "dclid",
    "gbraid",
    "wbraid",
    "msclkid",
    "yclid",
    "twclid",
    "igshid",
    "igsh",
    "mc_cid",
    "mc_eid",
    "_hsenc",
    "_hsmi",
    "mkt_tok",
    "_openstat",
    "vero_conv",
    "vero_id",
    "wickedid",
    "rb_clickid",
    "s_cid",
    "elqtrackid",
    "elqtrack",
    "oly_anon_id",
    "oly_enc_id",
    "pk_campaign",
    "pk_kwd",
    "pk_source",
    "pk_medium",
    "piwik_campaign",
    "piwik_kwd",
];

/// Whether one query parameter name is a tracking parameter.
fn is_tracking_param(name: &str) -> bool {
    let lower = name.to_ascii_lowercase();
    lower.starts_with("utm_") || TRACKING_PARAMS.contains(&lower.as_str())
}

/// Removes the tracking parameters from an absolute URL.
///
/// Only the query is touched: the path, the fragment and the order of the
/// parameters that stay are exactly as the client wrote them. `None` when
/// there is no query or nothing to remove, so a caller can leave the request
/// head alone.
pub fn strip_tracking_params(url: &str) -> Option<String> {
    strip_query(url, is_tracking_param)
}

/// The engines whose results URL carries the user's own words in the open, so
/// that a `Referer` out of a results page is a copy of the search. The label
/// is matched where the engine name sits (`google.com`, `search.brave.com`,
/// `google.co.uk`), which keeps `google.com.evil.test` out.
const SEARCH_ENGINES: &[&str] = &[
    "google",
    "bing",
    "duckduckgo",
    "yandex",
    "brave",
    "ecosia",
    "startpage",
    "mojeek",
    "qwant",
];

/// The parameter each engine puts the words in: `q` is the common one, the
/// rest are the engines that name it differently.
const SEARCH_QUERY_PARAMS: &[&str] = &["q", "query", "text", "search_query", "p"];

/// Whether `host` belongs to a search engine the switch knows.
pub fn is_search_engine(host: &str) -> bool {
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    if host.is_empty() {
        return false;
    }
    SEARCH_ENGINES
        .iter()
        .any(|engine| crate::safesearch::engine_at(&host, engine))
}

/// Takes the search query out of an engine's URL.
///
/// Only an engine host is touched and only the parameter holding the words
/// goes: the results page the user asked for still has to load, so this is for
/// what leaves the page — the `Referer` on the link the user clicks and the
/// sub-resource requests that repeat the whole search URL. `None` for anything
/// else, so a caller leaves the head alone.
pub fn strip_search_query(url: &str) -> Option<String> {
    if !is_search_engine(crate::filter::url_host(url)) {
        return None;
    }
    strip_query(url, |name| {
        let lower = name.to_ascii_lowercase();
        SEARCH_QUERY_PARAMS.contains(&lower.as_str())
    })
}

/// The walk both strips share: the query is split on `&`, the pairs the
/// predicate names are dropped, and everything else keeps its bytes and its
/// order. `None` when nothing was dropped.
fn strip_query(url: &str, drop: impl Fn(&str) -> bool) -> Option<String> {
    let (without_fragment, fragment) = match url.find('#') {
        Some(index) => (&url[..index], &url[index..]),
        None => (url, ""),
    };
    let (path, query) = without_fragment.split_once('?')?;
    if query.is_empty() {
        return None;
    }
    let mut kept: Vec<&str> = Vec::new();
    let mut removed = false;
    for pair in query.split('&') {
        if pair.is_empty() {
            continue;
        }
        let name = pair.split_once('=').map_or(pair, |(name, _)| name);
        if drop(name) {
            removed = true;
        } else {
            kept.push(pair);
        }
    }
    if !removed {
        return None;
    }
    let mut out = String::with_capacity(url.len());
    out.push_str(path);
    if !kept.is_empty() {
        out.push('?');
        out.push_str(&kept.join("&"));
    }
    out.push_str(fragment);
    Some(out)
}

/// Whether a request is cross-site from the page that made it.
///
/// `Sec-Fetch-Site` is authoritative when the browser sends it. Without it
/// the referrer is compared by registrable-ish host: the same host, or one
/// nested under the other (`www.example.com` and `static.example.com`), is
/// first-party. No referrer means a navigation or a client that hides it —
/// both are left alone rather than guessed at.
pub fn is_third_party(request_host: &str, referer: Option<&str>, site: Option<&str>) -> bool {
    match site.map(str::trim) {
        Some(value) if value.eq_ignore_ascii_case("cross-site") => return true,
        Some(value)
            if value.eq_ignore_ascii_case("same-origin")
                || value.eq_ignore_ascii_case("same-site") =>
        {
            return false
        }
        _ => {}
    }
    let Some(referer) = referer.filter(|value| !value.is_empty()) else {
        return false;
    };
    let referer_host = url_host(referer);
    !same_site(request_host, referer_host)
}

/// Whether two hosts belong to the same site: `a.example.com` and
/// `b.example.com` are, `example.com.evil.test` is not.
fn same_site(left: &str, right: &str) -> bool {
    if left.is_empty() || right.is_empty() {
        return true;
    }
    registrable(left) == registrable(right)
}

/// The suffixes that are a site boundary on their own.
///
/// Comparing "the last two labels" is right for `example.com` and wrong for
/// everything below: `a.github.io` and `b.github.io` are two different people,
/// and `shop.example.co.uk` and `other.example.co.uk` are one. This is the part
/// of the Public Suffix List that decides which of those cases a host is; the
/// full list is a megabyte of data that changes weekly, and a host that is not
/// named here falls back to the last-two-labels rule the way the list's own
/// default does.
const PUBLIC_SUFFIXES: &[&str] = &[
    // Second levels in wide use, by country.
    "co.uk", "org.uk", "me.uk", "ac.uk", "gov.uk", "net.uk", "sch.uk",
    "com.au", "net.au", "org.au", "edu.au", "gov.au", "id.au",
    "co.nz", "net.nz", "org.nz",
    "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
    "co.kr", "or.kr", "ne.kr",
    "com.br", "net.br", "org.br",
    "com.cn", "net.cn", "org.cn", "gov.cn",
    "com.vn", "net.vn", "org.vn", "edu.vn", "gov.vn",
    "co.th", "in.th", "or.th",
    "co.id", "or.id", "web.id", "ac.id", "go.id",
    "com.tr", "net.tr", "org.tr",
    "co.za", "org.za", "net.za",
    "com.mx", "org.mx",
    "com.ar", "org.ar",
    "com.sg", "edu.sg", "gov.sg", "org.sg",
    "com.my", "org.my",
    "com.ph", "org.ph",
    "com.tw", "org.tw", "edu.tw",
    "com.hk", "org.hk", "edu.hk",
    "co.il", "org.il", "ac.il",
    "com.ua", "org.ua", "net.ua",
    "com.pl", "org.pl", "net.pl",
    "co.in", "net.in", "org.in", "ac.in",
    "com.es", "org.es", "nom.es",
    "co.ke", "co.ug", "co.tz",
    "com.pk", "com.ng", "com.co", "com.pe", "com.ve", "com.ec",
    "com.sa", "com.eg", "com.kw", "com.qa", "com.jo",
    // Hosting and publishing platforms: every name under one is its own site.
    "github.io", "gitlab.io", "pages.dev", "workers.dev", "netlify.app",
    "vercel.app", "web.app", "firebaseapp.com", "appspot.com",
    "herokuapp.com", "azurewebsites.net", "cloudfront.net", "s3.amazonaws.com",
    "blogspot.com", "wordpress.com", "tumblr.com", "weebly.com", "wixsite.com",
    "myshopify.com", "square.site", "substack.com", "neocities.org",
    "fly.dev", "onrender.com", "glitch.me", "surge.sh", "repl.co",
    "duckdns.org", "ngrok-free.app", "trycloudflare.com",
];

/// The part of a host that names the site: its last label, plus one more for
/// the name in front of it — three when the last two are a public suffix, four
/// when the last three are.
fn registrable(host: &str) -> String {
    let host = host.trim_end_matches('.').to_ascii_lowercase();
    let labels: Vec<&str> = host.split('.').collect();
    // An address has no labels to weigh, and a bare `localhost` has nothing in
    // front of it: both compare as themselves.
    if labels.len() < 3 || host.parse::<std::net::IpAddr>().is_ok() {
        return host;
    }
    let last3 = labels[labels.len() - 3..].join(".");
    if labels.len() > 3 && PUBLIC_SUFFIXES.contains(&last3.as_str()) {
        return labels[labels.len() - 4..].join(".");
    }
    let last2 = labels[labels.len() - 2..].join(".");
    if PUBLIC_SUFFIXES.contains(&last2.as_str()) {
        return labels[labels.len() - 3..].join(".");
    }
    last2
}

/// STUN/TURN ports: the signalling WebRTC uses to discover a public address.
/// The fixed ranges are IANA's; Google's STUN service uses the 19302 block.
pub fn is_webrtc_port(port: u16) -> bool {
    matches!(port, 3478 | 5349 | 19302..=19309)
}

/// Whether a UDP datagram is a STUN message.
///
/// The magic cookie at offset 4 is what tells STUN from every other UDP
/// protocol; the port alone would miss a server on a non-standard one. The
/// header checks keep a random datagram that happens to carry the cookie from
/// being dropped.
pub fn is_stun_datagram(payload: &[u8]) -> bool {
    if payload.len() < 20 || payload[0] & 0xc0 != 0 {
        return false;
    }
    // DTLS shares the leading zero bits; its record types (20..23) are where
    // the two protocols would otherwise collide.
    if matches!(payload[0], 20..=23) {
        return false;
    }
    if payload[4..8] != [0x21, 0x12, 0xa4, 0x42] {
        return false;
    }
    let len = u16::from_be_bytes([payload[2], payload[3]]) as usize;
    len.is_multiple_of(4) && len + 20 <= payload.len()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tracking_parameters_leave_the_rest_in_order() {
        let url = "https://shop.test/item?id=7&utm_source=news&fbclid=abc&color=red#top";
        assert_eq!(
            strip_tracking_params(url).as_deref(),
            Some("https://shop.test/item?id=7&color=red#top")
        );
    }

    #[test]
    fn an_utm_parameter_is_matched_by_prefix() {
        assert_eq!(
            strip_tracking_params("https://a.test/?utm_campaign=x&keep=1&utm_content=y").as_deref(),
            Some("https://a.test/?keep=1")
        );
    }

    #[test]
    fn a_url_without_tracking_parameters_is_untouched() {
        assert!(strip_tracking_params("https://a.test/?keep=1").is_none());
        assert!(strip_tracking_params("https://a.test/path").is_none());
        assert!(strip_tracking_params("https://a.test/#frag").is_none());
    }

    #[test]
    fn dropping_every_parameter_leaves_a_bare_path() {
        assert_eq!(
            strip_tracking_params("https://a.test/?gclid=x").as_deref(),
            Some("https://a.test/")
        );
    }

    #[test]
    fn a_cross_site_fetch_is_third_party() {
        assert!(is_third_party(
            "cdn.test",
            Some("https://shop.test/"),
            Some("cross-site")
        ));
        assert!(!is_third_party(
            "cdn.test",
            Some("https://shop.test/"),
            Some("same-site")
        ));
        assert!(!is_third_party(
            "cdn.test",
            Some("https://shop.test/"),
            Some("same-origin")
        ));
    }

    #[test]
    fn the_referrer_decides_when_the_fetch_header_is_missing() {
        assert!(!is_third_party(
            "www.shop.test",
            Some("https://shop.test/page"),
            None
        ));
        assert!(!is_third_party(
            "static.shop.test",
            Some("https://www.shop.test/"),
            None
        ));
        assert!(is_third_party(
            "tracker.test",
            Some("https://shop.test/"),
            None
        ));
        assert!(!is_third_party("shop.test", None, None));
    }

    #[test]
    fn a_shared_hosting_suffix_is_not_one_site() {
        // Two names under a hosting suffix belong to two different owners, so a
        // request between them is third-party even though one host is the
        // other's suffix.
        assert!(is_third_party(
            "b.github.io",
            Some("https://a.github.io/page"),
            None
        ));
        assert!(is_third_party(
            "other.netlify.app",
            Some("https://mine.netlify.app/"),
            None
        ));
        // And a country suffix is not the site either: the name in front of it
        // is.
        assert!(!is_third_party(
            "static.shop.co.uk",
            Some("https://www.shop.co.uk/page"),
            None
        ));
        assert!(is_third_party(
            "other.co.uk",
            Some("https://shop.co.uk/page"),
            None
        ));
        // An address is its own site, and `localhost` has no site around it.
        assert!(is_third_party("10.0.0.2", Some("http://10.0.0.1/"), None));
        assert!(!is_third_party("localhost", Some("http://localhost/"), None));
    }

    #[test]
    fn stun_is_recognised_by_its_magic_cookie() {
        let mut binding = [0u8; 20];
        binding[0] = 0x00;
        binding[1] = 0x01;
        binding[2] = 0x00;
        binding[3] = 0x00;
        binding[4..8].copy_from_slice(&[0x21, 0x12, 0xa4, 0x42]);
        assert!(is_stun_datagram(&binding));
        assert!(!is_stun_datagram(b"hello"));
        let mut dtls = binding;
        dtls[0] = 0x16;
        assert!(!is_stun_datagram(&dtls));
    }

    #[test]
    fn webrtc_ports_are_the_signalling_ones() {
        assert!(is_webrtc_port(3478));
        assert!(is_webrtc_port(5349));
        assert!(is_webrtc_port(19302));
        assert!(!is_webrtc_port(443));
        assert!(!is_webrtc_port(53));
    }

    #[test]
    fn the_webrtc_guard_covers_sdp_and_candidate_events() {
        assert!(WEBRTC_GUARD.contains("setLocalDescription"));
        assert!(WEBRTC_GUARD.contains("icecandidate"));
        assert!(WEBRTC_GUARD.contains("onicecandidate"));
        assert!(WEBRTC_GUARD.contains("typ host"));
    }

    #[test]
    fn a_search_engines_query_is_the_only_thing_that_leaves() {
        assert_eq!(
            strip_search_query("https://www.google.com/search?q=cats&num=10").as_deref(),
            Some("https://www.google.com/search?num=10")
        );
        assert_eq!(
            strip_search_query("https://yandex.ru/?text=cats&lr=0").as_deref(),
            Some("https://yandex.ru/?lr=0")
        );
        assert_eq!(
            strip_search_query("https://html.duckduckgo.com/?q=cats&t=xyz").as_deref(),
            Some("https://html.duckduckgo.com/?t=xyz")
        );
    }

    #[test]
    fn any_other_host_keeps_its_query() {
        assert!(strip_search_query("https://shop.test/search?q=shoes").is_none());
        assert!(strip_search_query("https://google.com.evil.test/search?q=cats").is_none());
        assert!(strip_search_query("https://shop.test/path").is_none());
    }

    #[test]
    fn the_engine_table_follows_the_label_not_the_suffix() {
        assert!(is_search_engine("www.google.co.uk"));
        assert!(is_search_engine("search.brave.com"));
        assert!(is_search_engine("html.duckduckgo.com"));
        assert!(!is_search_engine("google.com.evil.test"));
        assert!(!is_search_engine("notgoogle.com"));
        assert!(!is_search_engine(""));
    }

    #[test]
    fn the_push_guard_closes_every_way_to_subscribe() {
        assert!(PUSH_GUARD.contains("PushManager"));
        assert!(PUSH_GUARD.contains("subscribe"));
        assert!(PUSH_GUARD.contains("requestPermission"));
        assert!(PUSH_GUARD.contains("register"));
    }

    #[test]
    fn the_geolocation_guard_answers_both_position_calls() {
        assert!(GEOLOCATION_GUARD.contains("getCurrentPosition"));
        assert!(GEOLOCATION_GUARD.contains("watchPosition"));
        assert!(GEOLOCATION_GUARD.contains("clearWatch"));
        assert!(GEOLOCATION_GUARD.contains("User denied Geolocation"));
    }
}
