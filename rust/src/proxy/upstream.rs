//! Where the desktop proxy sends its traffic when an upstream is configured.
//!
//! The phone reaches a SOCKS5 or `CONNECT` endpoint through the tunnel engine,
//! which reads `Config::proxy` and negotiates before the first byte of payload.
//! The desktop listeners had no such path: `dial_marked` went straight at the
//! destination, so a proxy address saved in the app was stored, shown as "in
//! use", and never dialled. Turning a WARP client to `proxy` mode made that
//! visible from outside — `curl` through the app still left with the machine's
//! own address while the card said the client was in use.
//!
//! Here the same negotiation the engine uses is driven over a blocking socket,
//! once per flow, and the resulting stream is handed back to the pump in the
//! non-blocking regime it expects. One deliberate asymmetry: an upstream that
//! refuses the flow refuses the request. Falling back to a direct dial would
//! send traffic out unencrypted-by-the-endpoint the user asked for, which is
//! the one failure mode of a proxy setting that must never be silent.

use super::{dial_marked, CONNECT_TIMEOUT};
use crate::httpconnect::UpstreamHandshake;
use crate::packet::{ip_key_from, IpKey};
use crate::socks5::ProxyEndpoint;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream};
use std::sync::Arc;

/// The upstream the app has configured right now, if any.
///
/// Read from the state the settings save into, not from a copy taken when the
/// listeners were raised: the endpoint is changed while protection is running,
/// and a flow that starts a second later should use the new one.
pub(crate) fn current() -> Option<Arc<ProxyEndpoint>> {
    let state = crate::engine::core().state()?;
    let protection = state.protection();
    crate::state::config::proxy(&protection.proxy).0
}

/// Dials the upstream half of a flow.
///
/// Direct when no endpoint is set, through it when one is. `None` means the
/// flow has nowhere to go — the same answer `dial_marked` gives for a failed
/// dial, so a caller needs no second path.
pub(crate) fn dial_flow(addr: SocketAddr) -> Option<TcpStream> {
    match current() {
        None => dial_marked(addr),
        Some(endpoint) => {
            // Automatic routing keeps private destinations off the endpoint,
            // exactly as the tunnel's relay does: a LAN address sent to a
            // remote proxy would fail or, worse, name a private target for the
            // endpoint to look at.
            if endpoint.automatic && crate::socks5::bypasses_automatic(ip_key_from(addr.ip())) {
                crate::logbuf::push(&format!(
                    "proxy: automatic routing keeps {addr} off {}:{}",
                    endpoint.addr.as_ip(),
                    endpoint.port
                ));
                return dial_marked(addr);
            }
            match dial_through(&endpoint, addr) {
                Ok(stream) => Some(stream),
                Err(reason) => {
                    // The request is refused rather than sent directly: an
                    // upstream the user turned on is why the traffic is allowed
                    // out at all.
                    crate::logbuf::push(&format!(
                        "proxy: upstream {}:{} refused {addr}: {reason}",
                        endpoint.addr.as_ip(),
                        endpoint.port
                    ));
                    None
                }
            }
        }
    }
}

/// One flow through `endpoint`, negotiated and ready to carry bytes.
fn dial_through(endpoint: &ProxyEndpoint, dest: SocketAddr) -> Result<TcpStream, String> {
    let proxy_addr = SocketAddr::new(endpoint.addr.as_ip(), endpoint.port);
    let mut stream =
        dial_marked(proxy_addr).ok_or_else(|| format!("no connection to {proxy_addr}"))?;
    // The mark is applied and the socket is connected; the negotiation below is
    // the place to wait, and it waits with a deadline rather than forever.
    stream
        .set_nonblocking(false)
        .map_err(|e| format!("blocking mode: {e}"))?;
    let _ = stream.set_read_timeout(Some(CONNECT_TIMEOUT));
    let _ = stream.set_write_timeout(Some(CONNECT_TIMEOUT));
    negotiate(&mut stream, endpoint, ip_key_from(dest.ip()), dest.port())?;
    let _ = stream.set_read_timeout(None);
    let _ = stream.set_write_timeout(None);
    stream
        .set_nonblocking(true)
        .map_err(|e| format!("non-blocking mode: {e}"))?;
    Ok(stream)
}

/// Drives the handshake to completion over a blocking socket.
fn negotiate(
    stream: &mut TcpStream,
    endpoint: &ProxyEndpoint,
    dest: IpKey,
    port: u16,
) -> Result<(), String> {
    let mut handshake = UpstreamHandshake::connect(endpoint, dest, port).map_err(str::to_string)?;
    let mut buf = [0u8; 512];
    while !handshake.is_done() {
        if handshake.wants_write() {
            let out = handshake.take_output();
            if !out.is_empty() {
                stream
                    .write_all(&out)
                    .map_err(|e| format!("writing the handshake: {e}"))?;
            }
            if handshake.is_done() {
                break;
            }
        }
        let read = stream
            .read(&mut buf)
            .map_err(|e| format!("reading the reply: {e}"))?;
        if read == 0 {
            return Err("the endpoint closed during the handshake".to_string());
        }
        let leftover = handshake.on_bytes(&buf[..read]).map_err(str::to_string)?;
        if !leftover.is_empty() {
            // Both protocols end their reply at a boundary the client parses,
            // so payload here means the endpoint answered something this side
            // does not model. Saying so beats dropping bytes in silence.
            crate::logbuf::push(&format!(
                "proxy: upstream sent {} bytes with its reply",
                leftover.len()
            ));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::socks5::ProxyKind;
    use std::io::{BufRead, BufReader};
    use std::net::TcpListener;
    use std::thread;

    fn endpoint(port: u16, kind: ProxyKind, auth: Option<(&str, &str)>) -> ProxyEndpoint {
        let (username, password) = match auth {
            Some((u, p)) => (u.to_string(), p.to_string()),
            None => (String::new(), String::new()),
        };
        ProxyEndpoint {
            addr: ip_key_from("127.0.0.1".parse().unwrap()),
            port,
            username,
            password,
            automatic: false,
            kind,
        }
    }

    /// A SOCKS5 endpoint that accepts the first CONNECT and echoes afterwards,
    /// recording the greeting and the request frame it was given.
    /// The greeting the endpoint was handed and its CONNECT frame.
    type Recorded = (Vec<u8>, Vec<u8>);

    fn socks5_server() -> (u16, thread::JoinHandle<Recorded>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            let mut head = [0u8; 2];
            socket.read_exact(&mut head).unwrap();
            let mut greeting = head.to_vec();
            let mut methods = vec![0u8; head[1] as usize];
            socket.read_exact(&mut methods).unwrap();
            greeting.extend_from_slice(&methods);
            socket.write_all(&[5, 0]).unwrap();
            let mut head = [0u8; 4];
            socket.read_exact(&mut head).unwrap();
            let mut frame = head.to_vec();
            let rest = match head[3] {
                1 => 6,
                4 => 18,
                _ => 0,
            };
            let mut tail = vec![0u8; rest];
            socket.read_exact(&mut tail).unwrap();
            frame.extend_from_slice(&tail);
            socket
                .write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0x04, 0x38])
                .unwrap();
            let mut echoed = Vec::new();
            let _ = socket.read(&mut echoed);
            (greeting, frame)
        });
        (port, handle)
    }

    #[test]
    fn a_configured_socks5_endpoint_carries_the_flow() {
        let (port, server) = socks5_server();
        let stream = dial_through(
            &endpoint(port, ProxyKind::Socks5, None),
            "1.2.3.4:443".parse().unwrap(),
        );
        let mut stream = stream.expect("the endpoint accepted the CONNECT");
        stream.set_nonblocking(false).unwrap();
        stream.write_all(b"hello").unwrap();
        let (greeting, frame) = server.join().unwrap();
        // One method offered, no credentials asked for.
        assert_eq!(greeting, vec![5, 1, 0]);
        // CONNECT, then the destination as an IPv4 literal and its port.
        assert_eq!(&frame[..4], &[5, 1, 0, 1]);
        assert_eq!(&frame[4..], &[1, 2, 3, 4, 0x01, 0xbb]);
        drop(stream);
    }

    #[test]
    fn credentials_travel_with_the_greeting() {
        let (port, server) = socks5_server();
        let _ = dial_through(
            &endpoint(port, ProxyKind::Socks5, Some(("u", "p"))),
            "1.2.3.4:443".parse().unwrap(),
        )
        .expect("the endpoint accepted the CONNECT");
        let (greeting, _) = server.join().unwrap();
        // Two methods: none, then username/password.
        assert_eq!(greeting, vec![5, 2, 0, 2]);
    }

    #[test]
    fn an_http_endpoint_is_told_where_the_flow_goes() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = thread::spawn(move || {
            let (socket, _) = listener.accept().unwrap();
            let mut reader = BufReader::new(socket);
            let mut first = String::new();
            reader.read_line(&mut first).unwrap();
            let mut line = String::new();
            loop {
                line.clear();
                reader.read_line(&mut line).unwrap();
                if line == "\r\n" || line.is_empty() {
                    break;
                }
            }
            reader
                .get_mut()
                .write_all(b"HTTP/1.1 200 Connection Established\r\n\r\n")
                .unwrap();
            first
        });
        let _ = dial_through(
            &endpoint(port, ProxyKind::Http, None),
            "1.2.3.4:443".parse().unwrap(),
        )
        .expect("the endpoint accepted the CONNECT");
        let first = handle.join().unwrap();
        assert_eq!(first.trim_end(), "CONNECT 1.2.3.4:443 HTTP/1.1");
    }

    #[test]
    fn a_refused_upstream_refuses_the_flow() {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        let handle = thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            let mut greeting = [0u8; 3];
            let _ = socket.read_exact(&mut greeting);
            // No acceptable method.
            socket.write_all(&[5, 0xff]).unwrap();
        });
        let refusal = dial_through(
            &endpoint(port, ProxyKind::Socks5, None),
            "1.2.3.4:443".parse().unwrap(),
        );
        assert!(refusal.is_err(), "a refused handshake is a refused flow");
        handle.join().unwrap();
    }

    #[test]
    fn nothing_configured_leaves_the_dial_direct() {
        // No endpoint is installed in the test process, so this is the direct
        // path: a closed port on loopback is the cheapest way to prove a dial
        // happened at all.
        let port = {
            let listener = TcpListener::bind("127.0.0.1:0").unwrap();
            listener.local_addr().unwrap().port()
        };
        let dest: SocketAddr = format!("127.0.0.1:{port}").parse().unwrap();
        assert!(dial_flow(dest).is_none());
    }
}
