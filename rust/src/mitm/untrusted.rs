//! Hosts whose client refused this proxy's certificate.
//!
//! Filtering assumes the client trusts the CA the engine holds. Some do not and
//! never will: a Node binary carries its own root set inside itself, a Rust
//! program can link `webpki-roots`, a game client can pin a certificate. No
//! store this install can write reaches any of them — `/etc/ssl/certs`, the NSS
//! databases and every JDK keystore are all consulted only by libraries that
//! were built to consult them.
//!
//! For those clients the only way to stay usable is not to terminate the TLS at
//! all: the flow is spliced verbatim, the app keeps working, and DNS filtering
//! still applies to it. What makes that automatic is the evidence a client
//! leaves when it refuses — a TLS alert naming a certificate problem. The proxy
//! sees it at the handshake, remembers the pair, and splices that host for that
//! client from then on.
//!
//! The pair is `(host, client fingerprint)` rather than the host alone, because
//! the same host is usually reached by a browser too: learning `api.example`
//! from a Node script must not cost Chrome its filtering on that host. The
//! fingerprint is the shape of the ClientHello (cipher suites and extension
//! types), which every distinct TLS stack gets wrong in its own way
//! (`tls::client_hello_fingerprint`) — a browser and a `fetch()` call do not
//! look alike.
//!
//! The set is bounded and deliberately not persisted: it is rebuilt by one
//! failed handshake per app per engine life, and a persisted copy would outlive
//! the reason it was learned (the user installing the CA into that app's store,
//! or the app being replaced by one that trusts it).

use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};

/// How many hosts the set may hold. A client that refuses one host refuses the
/// handful it talks to; the cap only exists so a hostile or broken client
/// cannot grow the map without end.
const MAX_HOSTS: usize = 256;

/// Distinct hosts a client has to refuse before it counts as a client whose
/// trust store does not hold the CA. One refusal can be a single bad
/// connection; two hosts is a store. A Node CLI meets a new API host far more
/// often than it meets a new trust store, and making the user watch a failed
/// connection per host for that is pointless.
const HOSTS_TO_GENERALISE: usize = 2;

/// How many `(host, client)` pairs that carried a request may be remembered.
/// Only read to veto a weak inference, so it can be generously sized while
/// still bounded.
const MAX_SERVED: usize = 4096;

/// The `state` row the learned pairs survive a restart in.
const STATE_KEY: &str = "mitm.untrusted_clients";

/// What the row holds: hosts → the clients that refused them.
///
/// Versioned because the meaning of a stored pair changed once already. The
/// first shape was the bare map, written when a silent close from a browser's
/// preconnect counted as a refusal, so it can hold pairs that were never
/// refusals at all — and a stored pair is believed before the host is ever
/// served, which leaves no way back. Such a row is dropped rather than
/// migrated: the cost of learning a real refusal again is one handshake.
#[derive(serde::Serialize, serde::Deserialize)]
struct Persisted {
    version: u32,
    hosts: HashMap<String, HashSet<String>>,
}

const STATE_VERSION: u32 = 3;

/// The remembered `(host → client fingerprints)` pairs.
#[derive(Default)]
pub struct UntrustedClients {
    /// One entry per host, holding the fingerprints seen refusing it.
    entries: Mutex<HashMap<String, HashSet<String>>>,
    /// The hosts each fingerprint has refused, which is what tells one bad
    /// connection apart from a client whose trust store does not hold the CA.
    hosts_by_fingerprint: Mutex<HashMap<String, HashSet<String>>>,
    /// The `(host, fingerprint)` pairs that have carried at least one real
    /// request. A pair in here has already proved it accepts this certificate:
    /// whatever a later silent close means, it does not mean that.
    served: Mutex<HashSet<(String, String)>>,
    /// The fingerprints that have carried a request *anywhere*. A client whose
    /// shape is in here is a working TLS stack that some connection reached an
    /// origin with, so a silent close from it is a preconnect being dropped —
    /// never a refused certificate. Without this a browser's own speculative
    /// connections can teach the proxy to splice a host it filters perfectly
    /// well, and nothing ever un-learns it: the pair is consulted before the
    /// host is served, so a poisoned pair can never serve again.
    served_fingerprints: Mutex<HashSet<String>>,
    /// The database the set is written back to, once the platform has handed
    /// the data root down. Without it the set lives as long as the engine,
    /// which is what a platform that never calls `set_store` gets.
    store: Mutex<Option<Arc<crate::store::Store>>>,
}

impl UntrustedClients {
    pub(crate) fn new() -> Arc<Self> {
        Arc::new(Self::default())
    }

    /// Records that the client behind `fingerprint` rejected the certificate
    /// for `host`. Returns true when this is news, so the caller logs once
    /// rather than on every retry.
    pub(crate) fn note(&self, host: &str, fingerprint: &str) -> bool {
        if host.is_empty() || host == "?" || fingerprint.is_empty() {
            return false;
        }
        let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        if !entries.contains_key(host) && entries.len() >= MAX_HOSTS {
            // Too many to remember: filtering everywhere is the safer default
            // for the hosts that no longer fit.
            return false;
        }
        let learned = entries
            .entry(host.to_string())
            .or_default()
            .insert(fingerprint.to_string());
        drop(entries);
        if learned {
            self.remember_host(fingerprint, host);
            self.save();
        }
        learned
    }

    /// Records that this fingerprint refused `host`, whatever the host was.
    fn remember_host(&self, fingerprint: &str, host: &str) {
        if fingerprint.is_empty() {
            return;
        }
        self.hosts_by_fingerprint
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .entry(fingerprint.to_string())
            .or_default()
            .insert(host.to_string());
    }

    /// Records that this pair talked to the origin through us.
    ///
    /// The evidence a certificate refusal leaves behind — a closed socket with
    /// no request — is the same shape a browser's speculative preconnect
    /// leaves, and a preconnect is followed by a real connection to the same
    /// host that works. Serving that connection is what tells the two apart, so
    /// it is remembered before any silent close is believed.
    pub(crate) fn note_served(&self, host: &str, fingerprint: &str) {
        if host.is_empty() || host == "?" || fingerprint.is_empty() {
            return;
        }
        let mut served = self.served.lock().unwrap_or_else(|e| e.into_inner());
        if served.len() < MAX_SERVED {
            served.insert((host.to_string(), fingerprint.to_string()));
            drop(served);
            self.served_fingerprints
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .insert(fingerprint.to_string());
        }
    }

    /// Records a refusal inferred from the *shape* of a connection rather than
    /// from an alert, and never lets it speak for other hosts.
    ///
    /// A client that completed our handshake and then closed without a request
    /// may have refused the certificate — OpenSSL-based stacks do exactly this
    /// and take the alert with them — but a browser preconnect looks identical.
    /// The inference is therefore weaker evidence, and is confined to the one
    /// pair: it can never generalise to "this client refuses everywhere", which
    /// would cost a browser its filtering on every other host on the strength
    /// of two speculative connections.
    pub(crate) fn note_weak(&self, host: &str, fingerprint: &str) -> bool {
        if host.is_empty() || host == "?" || fingerprint.is_empty() {
            return false;
        }
        // It already talked to us here: that is the certificate being accepted,
        // and the close was a preconnect being dropped.
        if self
            .served
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .contains(&(host.to_string(), fingerprint.to_string()))
        {
            return false;
        }
        // The same client talked to us somewhere else, so its trust store holds
        // the CA and this close is a preconnect. Chrome opens and drops them
        // for the host a page is about to navigate to; believing those marked
        // www.youtube.com as a refusing client seven times over and left every
        // cosmetic rule on the site unapplied.
        if self
            .served_fingerprints
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .contains(fingerprint)
        {
            return false;
        }
        let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        if !entries.contains_key(host) && entries.len() >= MAX_HOSTS {
            return false;
        }
        let learned = entries
            .entry(host.to_string())
            .or_default()
            .insert(fingerprint.to_string());
        drop(entries);
        if learned {
            self.save();
        }
        learned
    }

    /// Drops what was learned about one host, or everything.
    ///
    /// The verdict outlives the reason it was learned: a CA reinstalled into a
    /// client's trust store, or a pair learned from a preconnect, leaves a host
    /// spliced with no visible cause and no way back. Returns how many hosts
    /// were forgotten.
    pub fn forget(&self, host: Option<&str>) -> usize {
        let mut entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        let dropped = match host {
            Some(host) => entries.remove(host).map(|_| 1).unwrap_or(0),
            None => {
                let len = entries.len();
                entries.clear();
                len
            }
        };
        drop(entries);
        if let Some(host) = host {
            self.served
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .retain(|(h, _)| h != host);
            self.prune_served_fingerprints();
        } else {
            self.served
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clear();
            self.served_fingerprints
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clear();
        }
        if dropped > 0 {
            self.rebuild_fingerprints();
            self.save();
        }
        dropped
    }

    /// Re-derives the per-client host counts from the pairs that remain.
    fn rebuild_fingerprints(&self) {
        let entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        let mut by_fingerprint: HashMap<String, HashSet<String>> = HashMap::new();
        for (host, fingerprints) in entries.iter() {
            for fingerprint in fingerprints {
                by_fingerprint
                    .entry(fingerprint.clone())
                    .or_default()
                    .insert(host.clone());
            }
        }
        drop(entries);
        *self
            .hosts_by_fingerprint
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = by_fingerprint;
    }

    /// Drops the fingerprints whose served pair was forgotten, so the veto on
    /// weak evidence stays a statement about pairs that still exist.
    fn prune_served_fingerprints(&self) {
        let served = self.served.lock().unwrap_or_else(|e| e.into_inner());
        let live: HashSet<String> = served.iter().map(|(_, f)| f.clone()).collect();
        drop(served);
        self.served_fingerprints
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .retain(|f| live.contains(f));
    }

    /// True when this client has refused the CA on enough hosts that it is the
    /// client's trust store refusing, not one host's connection.
    pub(crate) fn refuses_anywhere(&self, fingerprint: &str) -> bool {
        if fingerprint.is_empty() {
            return false;
        }
        self.hosts_by_fingerprint
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(fingerprint)
            .is_some_and(|hosts| hosts.len() >= HOSTS_TO_GENERALISE)
    }

    /// Whether this client has already refused the certificate for `host`.
    ///
    /// An unknown fingerprint never matches: the pair is the whole evidence,
    /// and splicing a host for a client that never complained would give its
    /// traffic away unfiltered on the strength of somebody else's failure.
    pub(crate) fn refuses(&self, host: &str, fingerprint: &str) -> bool {
        if fingerprint.is_empty() {
            return false;
        }
        let exact = self
            .entries
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(host)
            .is_some_and(|fingerprints| fingerprints.contains(fingerprint));
        exact || self.refuses_anywhere(fingerprint)
    }

    /// The fingerprints remembered for one host, sorted: what a test needs to
    /// say that the pair was learned and nothing wider was. Test-only, because
    /// nothing decides on it — the log reads [`Self::snapshot`].
    #[cfg(test)]
    pub(crate) fn fingerprints(&self, host: &str) -> Vec<String> {
        let entries = self.entries.lock().unwrap_or_else(|e| e.into_inner());
        let mut found: Vec<String> = entries
            .get(host)
            .map(|set| set.iter().cloned().collect())
            .unwrap_or_default();
        found.sort();
        found
    }

    pub(crate) fn snapshot(&self) -> Vec<(String, usize)> {
        let mut hosts: Vec<(String, usize)> = self
            .entries
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .iter()
            .map(|(host, fingerprints)| (host.clone(), fingerprints.len()))
            .collect();
        hosts.sort();
        hosts
    }

    /// Whether anything was learned at all: the caller keeps the log quiet
    /// unless it was.
    pub(crate) fn is_empty(&self) -> bool {
        self.entries
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .is_empty()
    }

    /// Forgets every pair. The tests use it; so would a "start over" button.
    pub(crate) fn clear(&self) {
        self.entries
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
        self.hosts_by_fingerprint
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
        self.served
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
        self.save();
    }

    /// Points the set at the store: loads what an earlier run learned and
    /// writes every later change back.
    ///
    /// Persisted because the evidence costs one failed handshake per app, and
    /// a restart is not a reason to make the user watch that again. Installing
    /// the system CA clears the set, so a stale verdict cannot outlive the
    /// certificate it was learned against.
    pub fn set_store(&self, store: Arc<crate::store::Store>) {
        // An engine that ran before this was a database row may have left its
        // own file behind; it is not read any more, so it is not left to
        // suggest otherwise.
        std::fs::remove_file(store.root().join("untrusted-clients.json")).ok();
        if let Some(text) = store.state_get(STATE_KEY) {
            // The first shape was the bare map, with no version to read. It is
            // the shape that could hold a browser preconnect as a refusal, so
            // it is not migrated — only the count of what goes is reported.
            let version = serde_json::from_str::<serde_json::Value>(&text)
                .ok()
                .and_then(|v| v.get("version")?.as_u64());
            match version {
                Some(v) if v == STATE_VERSION as u64 => {
                    match serde_json::from_str::<Persisted>(&text) {
                        Ok(learned) => {
                            let mut entries =
                                self.entries.lock().unwrap_or_else(|e| e.into_inner());
                            for (host, fingerprints) in learned.hosts.into_iter().take(MAX_HOSTS) {
                                for fingerprint in &fingerprints {
                                    self.remember_host(fingerprint, &host);
                                }
                                entries.entry(host).or_default().extend(fingerprints);
                            }
                        }
                        Err(e) => crate::logbuf::push_error(&format!(
                            "mitm: unreadable certificate-refusal memory: {e}"
                        )),
                    }
                }
                Some(v) => crate::logbuf::push(&format!(
                    "mitm: dropping the certificate-refusal memory of an older build (version {v})"
                )),
                None => crate::logbuf::push(
                    "mitm: dropping the certificate-refusal memory of an older build (unversioned)",
                ),
            }
        }
        *self.store.lock().unwrap_or_else(|e| e.into_inner()) = Some(store);
    }

    /// Writes the current set out, if the platform gave it somewhere to go.
    ///
    /// One row in the same database as everything else the app remembers: the
    /// write is a single upsert into a page already in cache, and the set is
    /// small (one entry per app that refused, not per connection).
    fn save(&self) {
        let store = self.store.lock().unwrap_or_else(|e| e.into_inner()).clone();
        let Some(store) = store else {
            return;
        };
        let snapshot: HashMap<String, HashSet<String>> = self
            .entries
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        let body = match serde_json::to_string(&Persisted {
            version: STATE_VERSION,
            hosts: snapshot,
        }) {
            Ok(body) => body,
            Err(e) => {
                crate::logbuf::push_error(&format!("mitm: cannot encode the refusal memory: {e}"));
                return;
            }
        };
        if let Err(e) = store.state_set(STATE_KEY, &body) {
            crate::logbuf::push_error(&format!("mitm: cannot save the refusal memory: {e}"));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_refusal_is_remembered_per_host_and_client() {
        let set = UntrustedClients::new();
        assert!(set.note("api.example", "node-fp"));
        assert!(set.refuses("api.example", "node-fp"));
        // The same host from another TLS stack is somebody else's trust store.
        assert!(!set.refuses("api.example", "chrome-fp"));
        // And the same client on another host was never refused.
        assert!(!set.refuses("other.example", "node-fp"));
    }

    #[test]
    fn learning_the_same_pair_twice_is_not_news() {
        let set = UntrustedClients::new();
        assert!(set.note("api.example", "node-fp"));
        assert!(!set.note("api.example", "node-fp"));
        assert_eq!(vec![("api.example".to_string(), 1)], set.snapshot());
    }

    #[test]
    fn an_unknown_fingerprint_never_matches() {
        let set = UntrustedClients::new();
        set.note("api.example", "node-fp");
        assert!(!set.refuses("api.example", ""));
    }

    #[test]
    fn a_host_without_a_name_is_never_remembered() {
        let set = UntrustedClients::new();
        assert!(!set.note("", "node-fp"));
        assert!(!set.note("?", "node-fp"));
        assert!(set.is_empty());
    }

    #[test]
    fn the_set_stops_growing_at_the_cap() {
        let set = UntrustedClients::new();
        for i in 0..MAX_HOSTS {
            assert!(set.note(&format!("h{i}.example"), "node-fp"));
        }
        assert!(!set.note("one-too-many.example", "node-fp"));
        // A host already present keeps learning new clients.
        assert!(set.note("h0.example", "other-fp"));
        assert_eq!(MAX_HOSTS, set.snapshot().len());
    }

    /// A silent close is weak evidence and must stay confined to its host: a
    /// browser's preconnects look exactly like a refusal, and two of them would
    /// otherwise cost that browser its filtering on every other site.
    #[test]
    fn a_silent_close_never_generalises_to_other_hosts() {
        let set = UntrustedClients::new();
        assert!(set.note_weak("one.example", "chrome-fp"));
        assert!(set.note_weak("two.example", "chrome-fp"));
        assert!(set.refuses("one.example", "chrome-fp"));
        // The strong inference would have condemned the whole client here.
        assert!(!set.refuses("three.example", "chrome-fp"));
    }

    /// A pair that has carried a request accepts the certificate; whatever a
    /// later close means, it is not a refusal.
    #[test]
    fn a_pair_that_already_talked_to_us_is_never_learned_from_a_close() {
        let set = UntrustedClients::new();
        set.note_served("shop.example", "chrome-fp");
        assert!(!set.note_weak("shop.example", "chrome-fp"));
        assert!(!set.refuses("shop.example", "chrome-fp"));
        // Another client on the same host is still judged on its own close.
        assert!(set.note_weak("shop.example", "node-fp"));
    }

    /// A client that has carried a request anywhere is not refusing the CA: a
    /// silent close from it is a speculative connection being dropped.
    ///
    /// Chrome preconnects to the host it is about to navigate to and drops the
    /// sockets it does not use. Those closes taught the proxy that
    /// www.youtube.com was refused seven times over, and since a learned pair is
    /// believed before the host is ever served, the site then travelled
    /// spliced: no injected runtime, no cosmetic rules, and the home page's
    /// sponsored cards came back with nothing in the log to explain it.
    #[test]
    fn a_client_that_talked_to_us_is_never_learned_from_a_close() {
        let set = UntrustedClients::new();
        // Chrome reaches some other host with the same TLS shape…
        set.note_served("accounts.example", "chrome-fp");
        // …so its preconnects elsewhere say nothing about its trust store.
        assert!(!set.note_weak("www.example", "chrome-fp"));
        assert!(!set.refuses("www.example", "chrome-fp"));
        // A stack that never carried a request is still learned from a close:
        // that is the Node-shaped case the inference exists for.
        assert!(set.note_weak("www.example", "node-fp"));
        // Forgetting what a fingerprint served re-opens the question for it.
        set.forget(Some("accounts.example"));
        assert!(set.note_weak("www.example", "chrome-fp"));
    }

    /// An alert is the client saying it refused the certificate, and that one
    /// may speak for the client everywhere.
    #[test]
    fn an_alert_still_generalises() {
        let set = UntrustedClients::new();
        assert!(set.note("one.example", "node-fp"));
        assert!(set.note("two.example", "node-fp"));
        assert!(set.refuses("anything.example", "node-fp"));
    }

    /// A verdict outlives its reason — a reinstalled CA, a preconnect learned
    /// by mistake — so there has to be a way back that leaves other hosts alone.
    #[test]
    fn forgetting_one_host_leaves_the_others_spliced() {
        let set = UntrustedClients::new();
        set.note("one.example", "node-fp");
        set.note("two.example", "node-fp");
        assert_eq!(1, set.forget(Some("one.example")));
        assert!(!set.refuses("one.example", "node-fp"));
        assert!(set.refuses("two.example", "node-fp"));
        // Two learned pairs generalised; one left is not a trust store verdict.
        assert!(!set.refuses("three.example", "node-fp"));
        assert_eq!(1, set.forget(None));
        assert_eq!(0, set.forget(None));
        assert!(set.is_empty());
    }

    /// A restart must not cost the same app another failed handshake: what was
    /// learned is written to the data root and read back by the next engine.
    #[test]
    fn the_set_survives_a_restart_through_the_store() {
        let root = std::env::temp_dir().join(format!(
            "adfree-untrusted-{}-{:?}",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0)
        ));
        let store = Arc::new(crate::store::Store::open(&root).expect("store"));

        let first = UntrustedClients::new();
        first.set_store(Arc::clone(&store));
        assert!(first.note("api.example.com", "node-fp"));

        // The next engine over the same database finds it already learned.
        let second = UntrustedClients::new();
        second.set_store(Arc::clone(&store));
        assert!(second.refuses("api.example.com", "node-fp"));
        assert!(!second.refuses("api.example.com", "chrome-fp"));

        // Installing the CA clears the memory in the database too.
        second.clear();
        let third = UntrustedClients::new();
        third.set_store(Arc::clone(&store));
        assert!(third.is_empty(), "a cleared set must not come back");
        assert!(!third.refuses("api.example.com", "node-fp"));

        drop(store);
        std::fs::remove_dir_all(&root).ok();
    }

    /// A Node CLI meets a new API host far more often than it meets a new
    /// trust store: after it has refused on two hosts, the refusal is its
    /// store's, not that host's, and every later host is spliced without
    /// making the user watch another failed connection.
    #[test]
    fn a_client_that_refuses_two_hosts_refuses_everywhere() {
        let set = UntrustedClients::new();
        set.note("one.example", "node-fp");
        assert!(
            !set.refuses("other.example", "node-fp"),
            "one refusal is not a verdict on the trust store"
        );
        set.note("two.example", "node-fp");
        assert!(
            set.refuses("brand-new.example", "node-fp"),
            "a client that refused twice is spliced everywhere"
        );
        assert!(
            !set.refuses("brand-new.example", "browser-fp"),
            "another client keeps being filtered"
        );
        // Two hosts, not two connections to one host.
        let single = UntrustedClients::new();
        single.note("same.example", "other-fp");
        single.note("same.example", "other-fp");
        assert!(!single.refuses("elsewhere.example", "other-fp"));
    }

    #[test]
    fn clearing_forgets_everything() {
        let set = UntrustedClients::new();
        set.note("api.example", "node-fp");
        set.clear();
        assert!(set.is_empty());
        assert!(!set.refuses("api.example", "node-fp"));
    }
}
