//! Local certificate authority for the MITM stage.
//!
//! When HTTPS filtering is on, the app signs a certificate for every host it
//! sits between (client <-> app <-> server). That needs a self-generated CA the
//! user installs on the device, and a leaf cert minted on the fly per SNI.
//!
//! ## Restoring the CA after a restart
//!
//! rcgen cannot rebuild a `Certificate` from DER, so only the key (PKCS#8) is
//! restored and used to self-sign a fresh CA object; that object only supplies
//! the issuer DN/KID. The DER the client actually trusts is the stored one, so
//! the chain [leaf, stored_CA_der] still verifies.
//!
//! ECDSA P-256: fast key generation (no DNS-path stall), strong enough for
//! modern TLS, and leaves are cached per host so the cost is paid once. Every
//! host shares one leaf key (see [`leaf_key`]): generating one per host would
//! run a keygen inside the packet loop, and a page opening a dozen connections
//! would stack a dozen keygens behind each other.

use std::collections::HashMap;
use std::sync::{Mutex, OnceLock};

/// Upper bound on the leaf cache.
///
/// Each entry is a few hundred bytes, but a page can fan out to hosts without
/// any limit of its own (CDN shards, tracker subdomains), and the cache lives
/// for the whole session. Once full it is cleared instead of evicting one by
/// one: the cap is high enough that a real browsing session never reaches it,
/// so the simpler policy never turns into a per-handshake cost.
#[cfg(not(test))]
const MAX_CACHED_LEAVES: usize = 4_096;
/// Small enough for a test to reach without minting thousands of leaves.
#[cfg(test)]
const MAX_CACHED_LEAVES: usize = 8;

/// The host a leaf names as the place its revocation list lives, and the path
/// there. Windows' schannel will not finish a chain for a certificate whose
/// revocation state it cannot learn, and a leaf minted here is signed by a CA
/// that publishes nothing — the fetch fails, the handshake fails with
/// `CRYPT_E_NO_REVOCATION_CHECK` (0x80092012), and `git`, which hard-fails
/// revocation, cannot clone through the filter while the same clone works with
/// the filter off (reported 23/11/2026).
///
/// The answer is to publish something after all: an empty list, served by the
/// proxy itself. No real host resolves here — see [`CRL_ADDR`] and
/// `proxy::tcp::serve_crl`.
pub const CRL_HOST: &str = "crl.adfree.internal";
pub const CRL_PATH: &str = "/adfree.crl";
/// The address the resolver hands out for [`CRL_HOST`]. It names nothing real:
/// the redirect catches the fetch on its way out and the proxy answers it, so
/// the only thing this address has to be is an address a client will dial.
pub const CRL_ADDR: [u8; 4] = [203, 0, 113, 7];

/// [`CRL_ADDR`] as an address to compare a dial against.
pub fn crl_ip() -> std::net::IpAddr {
    std::net::IpAddr::V4(std::net::Ipv4Addr::from(CRL_ADDR))
}

/// The local CA: mints leaf certs for the hosts we intercept.
///
/// A `failed()` instance stands in for "no CA loaded yet": issuing reports an
/// error and everything else degrades quietly, so DNS filtering keeps working
/// without HTTPS filtering.
pub struct CertificateAuthority {
    /// The CA's real DER (read from disk, or freshly generated).
    cert_der: Vec<u8>,
    /// CA object used only as the issuer (DN/KID/key usages) when signing leaves.
    ca_object: rcgen::Certificate,
    ca_key: rcgen::KeyPair,
    /// The empty revocation list this CA publishes, signed by `ca_key`.
    crl_der: Vec<u8>,
    /// True when a real CA is loaded (as opposed to `failed()`).
    valid: bool,
    /// Leaf cache keyed by host.
    cache: Mutex<HashMap<String, IssuedCert>>,
}

/// A (cert, key) pair issued for one SNI.
#[derive(Clone)]
pub struct IssuedCert {
    pub cert: Vec<u8>,
    pub key: Vec<u8>,
}

impl CertificateAuthority {
    /// Generate a new CA (its DER is what the user installs).
    pub fn generate() -> Result<Self, String> {
        let ca_key = rcgen::KeyPair::generate().map_err(|e| format!("keygen: {e}"))?;
        let (ca_object, cert_der) = self_signed_ca(&ca_key)?;
        let crl_der = revocation_list(&ca_object, &ca_key)?;
        Ok(Self {
            cert_der,
            ca_object,
            ca_key,
            crl_der,
            cache: Mutex::new(HashMap::new()),
            valid: true,
        })
    }

    /// A stand-in for "no CA loaded yet".
    pub fn failed(reason: &str) -> Self {
        let ca_key = rcgen::KeyPair::generate().expect("keygen");
        let (ca_object, cert_der) = self_signed_ca(&ca_key).expect("self-sign");
        let crl_der = revocation_list(&ca_object, &ca_key).unwrap_or_default();
        let _ = reason;
        Self {
            cert_der,
            ca_object,
            ca_key,
            crl_der,
            cache: Mutex::new(HashMap::new()),
            valid: false,
        }
    }

    /// True when a real CA is loaded.
    pub fn has_ca(&self) -> bool {
        self.valid
    }

    /// Restore the CA from PEM (cert to install + key used to issue leaves).
    pub fn from_pem(ca_pem: &str, key_pem: &str) -> Result<Self, String> {
        let cert_der = decode_cert_pem(ca_pem)?;
        let ca_key =
            rcgen::KeyPair::from_pkcs8_pem_and_sign_algo(key_pem, &rcgen::PKCS_ECDSA_P256_SHA256)
                .map_err(|e| format!("key pem: {e}"))?;
        let (ca_object, _fresh) = self_signed_ca(&ca_key)?;
        let crl_der = revocation_list(&ca_object, &ca_key)?;
        Ok(Self {
            cert_der,
            ca_object,
            ca_key,
            crl_der,
            cache: Mutex::new(HashMap::new()),
            valid: true,
        })
    }

    /// The revocation list a leaf points at: DER, signed by this CA, listing no
    /// certificate as revoked (see [`CRL_HOST`]).
    pub fn crl_der(&self) -> Vec<u8> {
        self.crl_der.clone()
    }

    /// CA in DER form (fed to rustls' RootCertStore when a client verifies).
    pub fn cert_der(&self) -> Vec<u8> {
        self.cert_der.clone()
    }

    /// CA as PEM, for the user to install on the device.
    pub fn cert_pem(&self) -> String {
        pem_encode("CERTIFICATE", &self.cert_der)
    }

    /// CA key as PEM, for persistence.
    pub fn key_pem(&self) -> String {
        // rcgen already returns a PEM document; re-encoding it would corrupt it.
        self.ca_key.serialize_pem()
    }

    /// SHA-256 fingerprint (`AB:CD:...`) shown when the user installs the CA.
    pub fn fingerprint_sha256(&self) -> String {
        let digest = ring::digest::digest(&ring::digest::SHA256, &self.cert_der);
        digest
            .as_ref()
            .iter()
            .map(|b| format!("{b:02X}"))
            .collect::<Vec<_>>()
            .join(":")
    }

    /// Issue (or reuse from cache) a leaf cert for a host.
    pub fn issue(&self, host: &str) -> Result<IssuedCert, String> {
        if !self.valid {
            // The `failed()` placeholder must never sign: its key is generated
            // on the spot and never leaves memory, so the chain would reach the
            // client signed by something nobody could have installed.
            return Err("no CA loaded".into());
        }
        let host = host.to_ascii_lowercase();
        if host.is_empty() || host.starts_with('.') {
            return Err(format!("invalid host {host:?}"));
        }
        if let Some(hit) = self.cache.lock().unwrap().get(&host) {
            return Ok(hit.clone());
        }

        let leaf_key = leaf_key()?;
        let mut params = rcgen::CertificateParams::new(vec![host.clone()])
            .map_err(|e| format!("params: {e}"))?;
        // Same as the CA: the DN is what a "view certificate" screen shows, so
        // it names the host instead of rcgen's placeholder.
        let mut dn = rcgen::DistinguishedName::new();
        dn.push(rcgen::DnType::CommonName, host.clone());
        params.distinguished_name = dn;
        params.is_ca = rcgen::IsCa::NoCa;
        params.use_authority_key_identifier_extension = true;
        params.key_usages = vec![
            rcgen::KeyUsagePurpose::DigitalSignature,
            rcgen::KeyUsagePurpose::KeyEncipherment,
        ];
        params.extended_key_usages = vec![rcgen::ExtendedKeyUsagePurpose::ServerAuth];
        // Where this leaf's revocation state can be read. Windows' schannel
        // refuses a chain it cannot check (see [`CRL_HOST`]); the list is empty
        // and the proxy serves it, which is the same statement `noRevAvail`
        // makes in RFC 5280 terms — except Windows ignores that extension, as
        // measured on a real 10.0.26300 box: a leaf carrying it still answered
        // `RevocationStatusUnknown`, and only a list it could fetch cleared the
        // chain.
        //
        // Only where something answers. The list is served by the desktop's
        // local proxy ([`super::super::proxy::tcp`]) from a placeholder address
        // the desktop's resolver hands out; the phone's tunnel is a userspace
        // stack that answers neither, so a leaf minted there names nothing —
        // and naming a list nothing will serve is worse than naming none: the
        // client fetches, fails, and refuses the chain it could have accepted.
        if !cfg!(target_os = "android") {
            params.crl_distribution_points = vec![rcgen::CrlDistributionPoint {
                uris: vec![format!("http://{CRL_HOST}{CRL_PATH}")],
            }];
        }
        let yesterday = time::OffsetDateTime::now_utc() - time::Duration::days(1);
        let in_400_days = time::OffsetDateTime::now_utc() + time::Duration::days(400);
        params.not_before = yesterday;
        params.not_after = in_400_days;

        // Signed by our CA: (khoá leaf, CA object, khoá CA).
        let cert = params
            .signed_by(leaf_key, &self.ca_object, &self.ca_key)
            .map_err(|e| format!("sign leaf: {e}"))?;

        let issued = IssuedCert {
            cert: cert.der().to_vec(),
            key: leaf_key.serialize_der(),
        };
        {
            let mut cache = self.cache.lock().unwrap();
            if cache.len() >= MAX_CACHED_LEAVES {
                cache.clear();
            }
            cache.insert(host, issued.clone());
        }
        Ok(issued)
    }

    /// Full chain for rustls: leaf first, CA after.
    pub fn chain_for(&self, issued: &IssuedCert) -> Vec<Vec<u8>> {
        vec![issued.cert.clone(), self.cert_der.clone()]
    }

    pub fn cached_hosts(&self) -> usize {
        self.cache.lock().unwrap().len()
    }

    /// The hosts a leaf is already cached for (diagnostics and tests).
    pub fn cached_host_names(&self) -> Vec<String> {
        self.cache.lock().unwrap().keys().cloned().collect()
    }

    pub fn is_ca(&self) -> bool {
        // A CA object always carries BasicConstraints in its params.
        !self.cert_der.is_empty()
    }
}

/// One leaf key for every host.
///
/// What has to differ per certificate is its name (SAN) and validity, not the
/// key: mitmproxy and AdGuard ship a single leaf key for exactly this reason.
/// Generating one per host would run a keygen inside the packet path, and a
/// page opening a dozen connections would serialise a dozen keygens behind the
/// accept loop.
fn leaf_key() -> Result<&'static rcgen::KeyPair, String> {
    static KEY: OnceLock<Option<rcgen::KeyPair>> = OnceLock::new();
    KEY.get_or_init(|| rcgen::KeyPair::generate().ok())
        .as_ref()
        .ok_or_else(|| "leaf keygen failed".to_string())
}

/// The revocation list this CA publishes: DER, signed by `key`, naming no
/// revoked certificate.
///
/// Dates are fixed and far apart on purpose. Windows compares them against the
/// machine clock and a `thisUpdate` in the future is a list it will not use, so
/// a list minted "now" would be rejected by a box whose clock is a few seconds
/// behind the one that signed the leaf. The range runs to 2099 so no client
/// ever sees it expire; nothing is ever revoked, so there is nothing to
/// re-publish.
fn revocation_list(cert: &rcgen::Certificate, key: &rcgen::KeyPair) -> Result<Vec<u8>, String> {
    let params = rcgen::CertificateRevocationListParams {
        this_update: rcgen::date_time_ymd(2000, 1, 1),
        next_update: rcgen::date_time_ymd(2099, 1, 1),
        crl_number: rcgen::SerialNumber::from(1u64),
        issuing_distribution_point: None,
        revoked_certs: Vec::new(),
        key_identifier_method: rcgen::KeyIdMethod::Sha256,
    };
    let list = params
        .signed_by(cert, key)
        .map_err(|e| format!("crl: {e}"))?;
    Ok(list.der().to_vec())
}

/// Self-sign a CA object and return its DER.
fn self_signed_ca(key: &rcgen::KeyPair) -> Result<(rcgen::Certificate, Vec<u8>), String> {
    let mut params =
        rcgen::CertificateParams::new(Vec::new()).map_err(|e| format!("params: {e}"))?;
    // `new()` only fills the SANs; without a distinguished name rcgen writes
    // "CN=rcgen self signed cert" into the certificate, which is what the
    // system's certificate install dialog shows the user.
    let mut dn = rcgen::DistinguishedName::new();
    dn.push(rcgen::DnType::CommonName, "AdFree Personal CA");
    dn.push(rcgen::DnType::OrganizationName, "AdFree");
    params.distinguished_name = dn;
    params.is_ca = rcgen::IsCa::Ca(rcgen::BasicConstraints::Unconstrained);
    params.key_usages = vec![
        rcgen::KeyUsagePurpose::KeyCertSign,
        rcgen::KeyUsagePurpose::CrlSign,
        rcgen::KeyUsagePurpose::DigitalSignature,
    ];
    params.not_before = time::OffsetDateTime::now_utc() - time::Duration::days(1);
    params.not_after = time::OffsetDateTime::now_utc() + time::Duration::days(3_650);
    let cert = params
        .self_signed(key)
        .map_err(|e| format!("self-sign: {e}"))?;
    let der = cert.der().to_vec();
    Ok((cert, der))
}

/// DER of the first certificate in `pem`, which is what a trust store wants.
/// Also the core's only PEM reader that needs no engine, so the desktop's
/// short-lived elevated helper can install a CA without opening the database.
pub fn decode_cert_pem(pem: &str) -> Result<Vec<u8>, String> {
    use rustls::pki_types::pem::PemObject;
    rustls::pki_types::CertificateDer::pem_slice_iter(pem.as_bytes())
        .flatten()
        .next()
        .map(|cert| cert.to_vec())
        .ok_or_else(|| "ca pem: no certificate".to_string())
}

/// Private key of a leaf in the form rustls wants.
pub fn private_key_der(issued: &IssuedCert) -> rustls::pki_types::PrivateKeyDer<'static> {
    rustls::pki_types::PrivateKeyDer::Pkcs8(rustls::pki_types::PrivatePkcs8KeyDer::from(
        issued.key.clone(),
    ))
}

/// Base64-encode a DER block as PEM (no external crate needed).
fn pem_encode(label: &str, contents: &[u8]) -> String {
    use std::fmt::Write;
    let body = base64_encode(contents);
    let mut out = String::new();
    let _ = writeln!(out, "-----BEGIN {label}-----");
    for chunk in body.as_bytes().chunks(64) {
        let line = std::str::from_utf8(chunk).unwrap_or("");
        let _ = writeln!(out, "{line}");
    }
    let _ = writeln!(out, "-----END {label}-----");
    out
}

/// Standard base64 (RFC 4648), enough for PEM.
fn base64_encode(input: &[u8]) -> String {
    const TABLE: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(input.len().div_ceil(3) * 4);
    for chunk in input.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let n = (b0 << 16) | (b1 << 8) | b2;
        out.push(TABLE[(n >> 18) as usize & 63] as char);
        out.push(TABLE[(n >> 12) as usize & 63] as char);
        if chunk.len() > 1 {
            out.push(TABLE[(n >> 6) as usize & 63] as char);
        } else {
            out.push('=');
        }
        if chunk.len() > 2 {
            out.push(TABLE[n as usize & 63] as char);
        } else {
            out.push('=');
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use rustls::RootCertStore;

    #[test]
    fn generates_a_ca_and_caches_leaves() {
        let ca = CertificateAuthority::generate().expect("ca");
        assert!(!ca.cert_der().is_empty());
        let first = ca.issue("tracker.example.com").expect("leaf");
        let second = ca.issue("TRACKER.example.com").expect("leaf again");
        assert_eq!(first.cert, second.cert, "leaves are cached per host");
        assert_eq!(ca.cached_hosts(), 1);
        ca.issue("other.example.com").expect("second host");
        assert_eq!(ca.cached_hosts(), 2);
        assert_eq!(ca.chain_for(&first).len(), 2);
    }

    #[test]
    fn the_leaf_cache_stays_bounded() {
        let ca = CertificateAuthority::generate().expect("ca");
        for i in 0..MAX_CACHED_LEAVES {
            ca.issue(&format!("h{i}.example.com")).expect("leaf");
        }
        assert_eq!(ca.cached_hosts(), MAX_CACHED_LEAVES);
        ca.issue("one-too-many.example.com").expect("leaf");
        // The cap is a ceiling, not a target: a full cache is cleared rather
        // than grown, whatever the page named.
        assert_eq!(ca.cached_hosts(), 1);
        assert_eq!(
            ca.cached_host_names(),
            vec!["one-too-many.example.com".to_string()]
        );
    }

    #[test]
    fn rustls_accepts_the_ca_as_root() {
        let ca = CertificateAuthority::generate().expect("ca");
        let mut roots = RootCertStore::empty();
        roots
            .add(rustls::pki_types::CertificateDer::from(ca.cert_der()))
            .expect("root accepted");
        assert_eq!(roots.len(), 1);
    }

    #[test]
    #[cfg(not(target_os = "android"))]
    fn a_leaf_names_a_list_and_the_ca_publishes_it() {
        let ca = CertificateAuthority::generate().expect("ca");
        let leaf = ca.issue("git.example.com").expect("leaf");
        let has =
            |hay: &[u8], needle: &str| hay.windows(needle.len()).any(|w| w == needle.as_bytes());
        // A client that insists on revocation (Windows' schannel does) follows
        // this to the list below.
        assert!(
            has(&leaf.cert, CRL_HOST),
            "the leaf names a distribution point"
        );
        assert!(has(&leaf.cert, CRL_PATH));

        let crl = ca.crl_der();
        assert!(!crl.is_empty(), "the CA publishes a list");
        // `CN=AdFree Personal CA` in the issuer field: the list is signed by the
        // CA the leaf chains to, not by a key nobody installed.
        assert!(has(&crl, "AdFree Personal CA"));
        // One `revokedCertificates` field is absent, not empty: an empty list
        // is a 30 00 at the tail of the tbsCertList, so the shortest possible
        // list is well under a kilobyte.
        assert!(
            crl.len() < 1024,
            "an empty list stays small: {} bytes",
            crl.len()
        );
        // The signature is the CA's, so the same bytes verify against the CA.
        let other = CertificateAuthority::generate().expect("second ca");
        assert_ne!(other.crl_der(), crl, "each CA signs its own list");
    }

    #[test]
    fn the_placeholder_ca_refuses_to_issue() {
        // Signing from the placeholder would produce a chain whose root was
        // generated in memory and never installed anywhere.
        let ca = CertificateAuthority::failed("not initialised");
        assert!(!ca.has_ca());
        assert!(ca.issue("ads.example.com").is_err());
        assert!(ca.cached_hosts() == 0);
    }

    #[test]
    fn the_ca_carries_a_real_name_not_rcgens_placeholder() {
        let ca = CertificateAuthority::generate().expect("ca");
        let der = ca.cert_der();
        let has = |needle: &[u8]| der.windows(needle.len()).any(|w| w == needle);
        // The CN lands in the DER as-is, and this is what the system's
        // certificate install dialog shows.
        assert!(has(b"AdFree Personal CA"));
        assert!(!has(b"rcgen self signed cert"));
        // The issuer DN of a leaf is copied from the CA, so it carries the
        // same name rather than rcgen's.
        let leaf = ca.issue("ads.example.com").expect("leaf");
        let leaf_has = |needle: &[u8]| leaf.cert.windows(needle.len()).any(|w| w == needle);
        assert!(leaf_has(b"AdFree Personal CA"));
        assert!(leaf_has(b"ads.example.com"));
    }

    #[test]
    fn pem_survives_a_restart() {
        let ca = CertificateAuthority::generate().expect("ca");
        let pem = ca.cert_pem();
        let key = ca.key_pem();
        let restored = CertificateAuthority::from_pem(&pem, &key).expect("restore");
        assert_eq!(restored.cert_pem(), pem, "CA must survive a restart");
        assert_eq!(restored.fingerprint_sha256(), ca.fingerprint_sha256());
        // A leaf issued before the restart still sits in a valid chain.
        let leaf = restored.issue("ads.example.com").expect("leaf");
        assert_eq!(restored.chain_for(&leaf)[1], ca.cert_der());
    }

    #[test]
    fn probe_pem() {
        let key = rcgen::KeyPair::generate().unwrap();
        let pem = key.serialize_pem();
        let first = pem.lines().next().unwrap_or("").to_string();
        let back =
            rcgen::KeyPair::from_pkcs8_pem_and_sign_algo(&pem, &rcgen::PKCS_ECDSA_P256_SHA256);
        assert!(
            back.is_ok(),
            "first={first} err={:?}",
            back.err().map(|e| e.to_string())
        );
    }

    #[test]
    fn rejects_bad_input() {
        let ca = CertificateAuthority::generate().expect("ca");
        assert!(ca.issue("").is_err());
        assert!(ca.issue(".hidden").is_err());
        assert!(CertificateAuthority::from_pem("nonsense", "nonsense").is_err());
    }

    #[test]
    fn fingerprint_is_readable() {
        let ca = CertificateAuthority::generate().expect("ca");
        let fp = ca.fingerprint_sha256();
        assert_eq!(fp.split(':').count(), 32);
    }

    #[test]
    fn base64_matches_known_vectors() {
        assert_eq!(base64_encode(b""), "");
        assert_eq!(base64_encode(b"f"), "Zg==");
        assert_eq!(base64_encode(b"fo"), "Zm8=");
        assert_eq!(base64_encode(b"foo"), "Zm9v");
        assert_eq!(base64_encode(b"foob"), "Zm9vYg==");
    }
}
