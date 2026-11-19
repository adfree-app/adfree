//! An HTTP proxy upstream: `CONNECT host:port` and then raw bytes.
//!
//! The other upstream the app speaks is SOCKS5 (`socks5.rs`), and the two look
//! different on the wire but not to the caller: both take a destination and
//! credentials, both hand back handshake bytes to write and digest what the
//! proxy answers, and both say when the tunnel is open. [`UpstreamHandshake`]
//! is the one type the relay holds so a config change does not fork the flow
//! machinery in two.
//!
//! HTTP proxies carry TCP only. There is no UDP over `CONNECT`, so
//! [`UpstreamHandshake::udp_associate`] refuses an HTTP endpoint instead of
//! pretending: a UDP flow then goes direct rather than through a tunnel that
//! could not exist.

use crate::packet::IpKey;
use crate::socks5::{ProxyEndpoint, ProxyKind, Socks5Handshake};

/// Longest answer read before the proxy is considered broken.
const MAX_REPLY_BYTES: usize = 16 * 1024;

#[derive(Clone, Copy, PartialEq, Eq)]
enum Phase {
    /// The `CONNECT` head is queued, waiting for the proxy's status line.
    Reply,
    Done,
    Failed,
}

pub struct HttpConnectHandshake {
    phase: Phase,
    /// Handshake bytes the proxy has not accepted yet.
    out: Vec<u8>,
    /// Proxy bytes not yet a whole head.
    rx: Vec<u8>,
}

impl HttpConnectHandshake {
    pub fn new(
        dest: IpKey,
        dest_port: u16,
        auth: Option<(String, String)>,
    ) -> Result<Self, &'static str> {
        let host = match dest {
            IpKey::V4(a) => std::net::Ipv4Addr::from(a.to_be_bytes()).to_string(),
            IpKey::V6(a) => format!("[{}]", std::net::Ipv6Addr::from(a)),
        };
        let authority = format!("{host}:{dest_port}");
        let mut head = format!(
            "CONNECT {authority} HTTP/1.1\r\nHost: {authority}\r\nProxy-Connection: keep-alive\r\n"
        );
        if let Some((user, pass)) = &auth {
            head.push_str("Proxy-Authorization: Basic ");
            head.push_str(&base64(format!("{user}:{pass}").as_bytes()));
            head.push_str("\r\n");
        }
        head.push_str("\r\n");
        Ok(Self {
            phase: Phase::Reply,
            out: head.into_bytes(),
            rx: Vec::new(),
        })
    }

    pub fn wants_write(&self) -> bool {
        !self.out.is_empty()
    }

    pub fn is_done(&self) -> bool {
        self.phase == Phase::Done
    }

    pub fn take_output(&mut self) -> Vec<u8> {
        std::mem::take(&mut self.out)
    }

    pub fn requeue_output(&mut self, bytes: &[u8]) {
        if bytes.is_empty() {
            return;
        }
        let mut merged = Vec::with_capacity(bytes.len() + self.out.len());
        merged.extend_from_slice(bytes);
        merged.extend_from_slice(&self.out);
        self.out = merged;
    }

    /// Consumes proxy bytes. Returns whatever followed the status head — the
    /// origin's own bytes coalesced into the same read — or why the flow dies.
    pub fn on_bytes(&mut self, bytes: &[u8]) -> Result<Vec<u8>, &'static str> {
        if self.phase == Phase::Failed {
            return Err("proxy handshake already failed");
        }
        if self.phase == Phase::Done {
            return Ok(bytes.to_vec());
        }
        self.rx.extend_from_slice(bytes);
        if self.rx.len() > MAX_REPLY_BYTES {
            self.phase = Phase::Failed;
            return Err("proxy reply is too long");
        }
        let Some(end) = head_end(&self.rx) else {
            return Ok(Vec::new());
        };
        let head = String::from_utf8_lossy(&self.rx[..end]).into_owned();
        let rest = self.rx[end..].to_vec();
        let mut lines = head.split("\r\n");
        let status = lines.next().unwrap_or_default();
        let code = status
            .split(' ')
            .nth(1)
            .and_then(|code| code.parse::<u16>().ok());
        match code {
            Some(code) if (200..300).contains(&code) => {
                self.phase = Phase::Done;
                Ok(rest)
            }
            Some(407) => {
                self.phase = Phase::Failed;
                Err("the HTTP proxy rejected the credentials")
            }
            Some(405) | Some(501) => {
                self.phase = Phase::Failed;
                Err("the HTTP proxy does not allow CONNECT")
            }
            Some(_) => {
                self.phase = Phase::Failed;
                Err("the HTTP proxy refused the connection")
            }
            None => {
                self.phase = Phase::Failed;
                Err("the HTTP proxy did not answer with a status line")
            }
        }
    }
}

/// Byte offset just past the blank line that ends a head.
fn head_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4)
        .position(|w| w == b"\r\n\r\n")
        .map(|at| at + 4)
}

/// Standard base64 with padding, for the one header that needs it.
fn base64(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let b = [
            chunk[0],
            chunk.get(1).copied().unwrap_or(0),
            chunk.get(2).copied().unwrap_or(0),
        ];
        let n = (u32::from(b[0]) << 16) | (u32::from(b[1]) << 8) | u32::from(b[2]);
        out.push(ALPHABET[(n >> 18) as usize & 63] as char);
        out.push(ALPHABET[(n >> 12) as usize & 63] as char);
        out.push(if chunk.len() > 1 {
            ALPHABET[(n >> 6) as usize & 63] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            ALPHABET[n as usize & 63] as char
        } else {
            '='
        });
    }
    out
}

/// The upstream handshake the relay holds: whichever protocol the endpoint's
/// config names, behind one interface.
pub enum UpstreamHandshake {
    Socks5(Socks5Handshake),
    Http(HttpConnectHandshake),
}

impl UpstreamHandshake {
    /// A TCP tunnel to `dest:port` through `endpoint`.
    pub fn connect(endpoint: &ProxyEndpoint, dest: IpKey, port: u16) -> Result<Self, &'static str> {
        match endpoint.kind {
            ProxyKind::Socks5 => {
                Socks5Handshake::new(dest, port, endpoint.credentials()).map(Self::Socks5)
            }
            ProxyKind::Http => {
                HttpConnectHandshake::new(dest, port, endpoint.credentials()).map(Self::Http)
            }
        }
    }

    /// A UDP association. SOCKS5 has one; HTTP has none, so an HTTP endpoint is
    /// a refusal the caller answers by going direct — visibly, not silently.
    pub fn udp_associate(endpoint: &ProxyEndpoint) -> Result<Self, &'static str> {
        match endpoint.kind {
            ProxyKind::Socks5 => match Socks5Handshake::new_udp_associate(endpoint.credentials()) {
                Ok(handshake) => Ok(Self::Socks5(handshake)),
                Err(reason) => Err(reason),
            },
            ProxyKind::Http => Err("an HTTP proxy cannot carry UDP"),
        }
    }

    pub fn wants_write(&self) -> bool {
        match self {
            Self::Socks5(h) => h.wants_write(),
            Self::Http(h) => h.wants_write(),
        }
    }

    pub fn is_done(&self) -> bool {
        match self {
            Self::Socks5(h) => h.is_done(),
            Self::Http(h) => h.is_done(),
        }
    }

    pub fn take_output(&mut self) -> Vec<u8> {
        match self {
            Self::Socks5(h) => h.take_output(),
            Self::Http(h) => h.take_output(),
        }
    }

    pub fn requeue_output(&mut self, bytes: &[u8]) {
        match self {
            Self::Socks5(h) => h.requeue_output(bytes),
            Self::Http(h) => h.requeue_output(bytes),
        }
    }

    pub fn on_bytes(&mut self, bytes: &[u8]) -> Result<Vec<u8>, &'static str> {
        match self {
            Self::Socks5(h) => h.on_bytes(bytes),
            Self::Http(h) => h.on_bytes(bytes),
        }
    }

    /// The relay address a SOCKS5 UDP ASSOCIATE reply named. HTTP never has one.
    pub fn bound(&self) -> Option<(IpKey, u16)> {
        match self {
            Self::Socks5(h) => h.bound(),
            Self::Http(_) => None,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn dest() -> IpKey {
        IpKey::V4(u32::from_be_bytes([93, 184, 216, 34]))
    }

    #[test]
    fn the_head_is_a_connect_to_the_authority() {
        let mut hs = HttpConnectHandshake::new(dest(), 443, None).expect("handshake");
        let head = String::from_utf8(hs.take_output()).expect("ascii");
        assert_eq!(
            head,
            "CONNECT 93.184.216.34:443 HTTP/1.1\r\n\
             Host: 93.184.216.34:443\r\n\
             Proxy-Connection: keep-alive\r\n\r\n"
        );
        assert!(!hs.is_done());
    }

    #[test]
    fn ipv6_authorities_are_bracketed() {
        let v6 = IpKey::V6(u128::from(std::net::Ipv6Addr::LOCALHOST));
        let mut hs = HttpConnectHandshake::new(v6, 8443, None).expect("handshake");
        let head = String::from_utf8(hs.take_output()).expect("ascii");
        assert!(
            head.starts_with("CONNECT [::1]:8443 HTTP/1.1\r\n"),
            "{head}"
        );
    }

    #[test]
    fn credentials_go_through_basic_auth() {
        let auth = ("user".to_string(), "pa:ss".to_string());
        let mut hs = HttpConnectHandshake::new(dest(), 443, Some(auth)).expect("handshake");
        let head = String::from_utf8(hs.take_output()).expect("ascii");
        assert!(
            head.contains("Proxy-Authorization: Basic dXNlcjpwYTpzcw==\r\n"),
            "{head}"
        );
    }

    #[test]
    fn a_status_head_opens_the_tunnel_and_hands_over_the_rest() {
        let mut hs = HttpConnectHandshake::new(dest(), 443, None).expect("handshake");
        hs.take_output();
        assert!(hs.on_bytes(b"HTTP/1.1 200").expect("partial").is_empty());
        assert!(!hs.is_done());
        let rest = hs
            .on_bytes(b" Connection established\r\n\r\n\x16\x03\x01")
            .expect("established");
        assert_eq!(rest, b"\x16\x03\x01");
        assert!(hs.is_done());
    }

    #[test]
    fn a_refusal_names_the_reason() {
        let mut hs = HttpConnectHandshake::new(dest(), 443, None).expect("handshake");
        hs.take_output();
        assert_eq!(
            hs.on_bytes(b"HTTP/1.1 407 Proxy Authentication Required\r\n\r\n")
                .expect_err("credentials"),
            "the HTTP proxy rejected the credentials"
        );

        let mut hs = HttpConnectHandshake::new(dest(), 443, None).expect("handshake");
        hs.take_output();
        assert_eq!(
            hs.on_bytes(b"HTTP/1.1 501 Not Implemented\r\n\r\n")
                .expect_err("no connect"),
            "the HTTP proxy does not allow CONNECT"
        );

        let mut hs = HttpConnectHandshake::new(dest(), 443, None).expect("handshake");
        hs.take_output();
        assert_eq!(
            hs.on_bytes(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
                .expect_err("refused"),
            "the HTTP proxy refused the connection"
        );
    }

    #[test]
    fn something_that_is_not_http_is_not_guessed_at() {
        let mut hs = HttpConnectHandshake::new(dest(), 443, None).expect("handshake");
        hs.take_output();
        assert_eq!(
            hs.on_bytes(b"\x05\x00\r\n\r\n").expect_err("not http"),
            "the HTTP proxy did not answer with a status line"
        );
    }

    #[test]
    fn an_endless_reply_is_cut_off() {
        let mut hs = HttpConnectHandshake::new(dest(), 443, None).expect("handshake");
        hs.take_output();
        hs.on_bytes(&vec![b'x'; MAX_REPLY_BYTES + 1])
            .expect_err("too long");
    }

    #[test]
    fn base64_matches_the_known_vectors() {
        assert_eq!(base64(b""), "");
        assert_eq!(base64(b"f"), "Zg==");
        assert_eq!(base64(b"fo"), "Zm8=");
        assert_eq!(base64(b"foo"), "Zm9v");
        assert_eq!(base64(b"user:pass"), "dXNlcjpwYXNz");
    }

    #[test]
    fn an_http_endpoint_has_no_udp() {
        let endpoint = ProxyEndpoint {
            addr: dest(),
            port: 8080,
            username: String::new(),
            password: String::new(),
            automatic: true,
            kind: ProxyKind::Http,
        };
        assert_eq!(
            UpstreamHandshake::udp_associate(&endpoint).err(),
            Some("an HTTP proxy cannot carry UDP")
        );
    }
}
