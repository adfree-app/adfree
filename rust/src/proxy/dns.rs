//! The DNS half: a UDP server that answers like the engine's `handle_dns`
//! does on the tun path — app firewall first, user rewrites, then the lists —
//! and forwards everything else to the configured resolver.
//!
//! Under the redirect rules (`tools/adfree-linux.nft`) the machine's port-53
//! traffic lands here. Without them the platform can point its own resolver
//! configuration (or an app's DNS setting) at this port; either way the
//! filtering is identical, because the tables are the engine's.
//!
//! Answering through a redirect is the reason this is `recvmsg`/`sendmsg`
//! rather than `recv_from`/`send_to`: a `redirect` in the output hook keeps
//! the destination address and only moves the port, so the query arrives at
//! the address its sender thought it was asking. Connection tracking reverses
//! the redirect only for a reply it can match to that address, and a resolver
//! discards an answer that appears to come from anywhere else — so the answer
//! has to be sent *from* the address the question was aimed at, which is what
//! the packet-info control message does.

#[cfg_attr(windows, allow(unused_imports))]
use super::{parse_sockaddr, poll_readable, sockaddr_for, try_mark, POLL_SLICE_MS};
use crate::dns;
use crate::engine::core;
use std::collections::HashSet;
use std::io::{Read as _, Write as _};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream, UdpSocket};
use crate::sys::fd::{AsRawFd, RawFd};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use crate::sys::libc;

/// Bytes credited to a refused lookup, matching the tun path's accounting.
const BYTES_PER_BLOCKED_REQUEST: u64 = 48 * 1024;

/// How long a forwarded query may wait for its upstream answer, across every
/// server it is tried against.
const FORWARD_TIMEOUT_MS: i32 = 5000;

/// How long one copy of a query waits before it is sent again.
///
/// Below the usual second of a resolver's own retry timer on purpose: the link
/// this runs over drops about half the datagrams and answers the rest in well
/// over a second, so three or four copies fit in the budget where one used to.
const ATTEMPT_MS: i32 = 700;

/// Room for the control messages of one datagram.
#[cfg_attr(windows, allow(dead_code))]
const CONTROL_BYTES: usize = 512;

/// True when this process can put the firewall mark on a socket, decided once.
///
/// Without it a forwarded query is redirected straight back into this listener
/// by the same rules that brought the client's query here: the mark is what
/// tells the two apart, so forwarding over UDP becomes a loop rather than a
/// lookup. An unprivileged run has no mark, and the fallback below is what
/// keeps its DNS answering.
static MARK_OK: AtomicBool = AtomicBool::new(false);

/// Source ports of the sockets currently forwarding upstream.
///
/// Under a redirect the upstream query comes back to this listener with its
/// sender's address intact — including the ephemeral port of the socket that
/// sent it. Recognising that port is how a loop is told from a client, so it is
/// kept rather than guessed at.
static FORWARD_PORTS: std::sync::Mutex<Option<HashSet<u16>>> = std::sync::Mutex::new(None);

/// One UDP listener and the family its control messages speak. Two sockets
/// rather than one dual-stack socket because the packet-info option that carries
/// the answer back to the address the client named is family-specific, and a
/// v4-mapped peer on an IPv6 socket would have to guess which one to attach.
#[cfg_attr(windows, allow(dead_code))]
pub(crate) struct Listener {
    socket: UdpSocket,
    v6: bool,
}

/// Where a query came from and where it arrived: the reply needs both.
#[cfg_attr(windows, allow(dead_code))]
struct Inquiry {
    body: Vec<u8>,
    peer: SocketAddr,
    /// The destination address the query was sent to. `None` when the socket
    /// did not report it (no packet-info option), in which case the answer is
    /// sent from whatever address the route picks.
    local: Option<IpAddr>,
    /// Interface index reported with it, needed by the IPv6 form.
    ifindex: u32,
}

/// The v4 and v6 listeners on `port`. The v4 one is required; IPv6 is dropped
/// when the machine has no v6 stack to bind.
pub(crate) fn bind_listeners(port: u16) -> std::io::Result<Vec<Listener>> {
    let mut listeners = Vec::new();
    let v4 = bind_one(IpAddr::V4(Ipv4Addr::UNSPECIFIED), port, false)?;
    listeners.push(v4);
    match bind_one(IpAddr::V6(Ipv6Addr::UNSPECIFIED), port, true) {
        Ok(v6) => listeners.push(v6),
        // No IPv6 here (or the stack hides it): the v4 half still serves every
        // query the redirect rules can produce on a machine without v6.
        Err(_) => crate::logbuf::push("proxy: no IPv6 DNS listener; answering IPv4 queries only"),
    }
    Ok(listeners)
}

fn bind_one(addr: IpAddr, port: u16, v6: bool) -> std::io::Result<Listener> {
    // The socket is built by hand rather than with `UdpSocket::bind` because
    // `IPV6_V6ONLY` has to be set *before* the bind, not after. Linux defaults
    // to `bindv6only=0`, so a `::` socket that is already bound has claimed the
    // v4 half of the port as well — and the v4 listener above holds it, so the
    // bind fails with `EADDRINUSE` and the machine is left with no IPv6
    // resolver at all. Setting the option on a bound socket is too late: the
    // kernel has already made the decision it describes.
    let domain = if v6 { libc::AF_INET6 } else { libc::AF_INET };
    let fd = unsafe {
        libc::socket(
            domain,
            libc::SOCK_DGRAM | libc::SOCK_CLOEXEC,
            0,
        )
    };
    if !crate::sys::fd::fd_ok(fd) {
        return Err(std::io::Error::last_os_error());
    }
    // Every early return past this point owns the fd and has to close it.
    let close_on_err = |fd: RawFd, err: std::io::Error| -> std::io::Result<Listener> {
        unsafe { libc::close(fd) };
        Err(err)
    };

    if v6 {
        // Paired with the v4 socket above, so this one must not also claim the
        // v4 half of the port. Set here, while the socket is still unbound.
        let on: libc::c_int = 1;
        sockopt(fd, libc::IPPROTO_IPV6, libc::IPV6_V6ONLY, &on as *const _ as *const _);
        sockopt(fd, libc::IPPROTO_IPV6, libc::IPV6_RECVPKTINFO, &on as *const _ as *const _);
    } else {
        let on: libc::c_int = 1;
        sockopt(fd, libc::SOL_IP, libc::IP_PKTINFO, &on as *const _ as *const _);
    }
    // The mark lets an unprivileged test run this path at all: answering a
    // redirected query means sending from an address the machine does not own,
    // which only a transparent socket may do. Losing it costs the redirect
    // layout, not the explicit-resolver layout.
    let on: libc::c_int = 1;
    let transparent = if v6 { libc::IPV6_TRANSPARENT } else { libc::IP_TRANSPARENT };
    let level = if v6 { libc::IPPROTO_IPV6 } else { libc::SOL_IP };
    sockopt(fd, level, transparent, &on as *const _ as *const _);

    let (sa, len) = sockaddr_for(SocketAddr::new(addr, port));
    let rc = unsafe {
        libc::bind(fd, &sa as *const libc::sockaddr_storage as *const libc::sockaddr, len)
    };
    if rc != 0 {
        return close_on_err(fd, std::io::Error::last_os_error());
    }

    // `FromRawFd` takes ownership, so the fd must not be closed after this.
    use crate::sys::fd::FromRawFd;
    let socket = unsafe { UdpSocket::from_raw_fd(fd) };
    #[cfg(windows)]
    {
        // Windows gets one blocking reader per socket instead of a shared
        // readiness poll: `WSAPoll` does not report a readable datagram socket
        // there — it waits out its full timeout on a socket that has a query
        // sitting in it — so the loop that trusted it answered nothing at all,
        // and a protected machine resolved no name. A read timeout is how that
        // loop notices `stop` instead.
        socket.set_read_timeout(Some(std::time::Duration::from_millis(
            POLL_SLICE_MS.max(50) as u64,
        )))?;
    }
    #[cfg(not(windows))]
    socket.set_nonblocking(true)?;
    Ok(Listener { socket, v6 })
}

fn sockopt(fd: RawFd, level: libc::c_int, opt: libc::c_int, val: *const libc::c_void) {
    unsafe {
        libc::setsockopt(
            fd,
            level,
            opt,
            val,
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        )
    };
}

impl Listener {
    fn fd(&self) -> RawFd {
        self.socket.as_raw_fd()
    }

    /// One query with the address it arrived at, and the address it was sent
    /// to when the kernel reports it.
    ///
    /// Linux only: the answer is sent back from the address the query named, and
    /// that name arrives as ancillary data on `recvmsg`.
    #[cfg(not(windows))]
    fn recv(&self, buf: &mut [u8]) -> Option<Inquiry> {
        let mut addr: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
        let mut ctl = [0u8; CONTROL_BYTES];
        let mut iov = libc::iovec {
            iov_base: buf.as_mut_ptr() as *mut libc::c_void,
            iov_len: buf.len(),
        };
        let mut hdr = libc::msghdr {
            msg_name: &mut addr as *mut libc::sockaddr_storage as *mut libc::c_void,
            msg_namelen: std::mem::size_of::<libc::sockaddr_storage>() as libc::socklen_t,
            msg_iov: &mut iov,
            msg_iovlen: 1,
            msg_control: ctl.as_mut_ptr() as *mut libc::c_void,
            msg_controllen: ctl.len(),
            msg_flags: 0,
        };
        let n = unsafe { libc::recvmsg(self.fd(), &mut hdr, 0) };
        if n <= 0 {
            return None;
        }
        let peer = parse_sockaddr(&addr)?;
        let mut local = None;
        let mut ifindex = 0;
        unsafe {
            let mut cmsg = libc::CMSG_FIRSTHDR(&hdr);
            while !cmsg.is_null() {
                let head = &*cmsg;
                if !self.v6 && head.cmsg_level == libc::SOL_IP && head.cmsg_type == libc::IP_PKTINFO
                {
                    let info = &*(libc::CMSG_DATA(cmsg) as *const libc::in_pktinfo);
                    // `ipi_addr` is the destination the datagram was delivered
                    // to, held in network order.
                    local =
                        Some(Ipv4Addr::from(u32::from_be(info.ipi_addr.s_addr)).into());
                    ifindex = info.ipi_ifindex as u32;
                } else if self.v6
                    && head.cmsg_level == libc::IPPROTO_IPV6
                    && head.cmsg_type == libc::IPV6_PKTINFO
                {
                    let info = &*(libc::CMSG_DATA(cmsg) as *const libc::in6_pktinfo);
                    local = Some(Ipv6Addr::from(info.ipi6_addr.s6_addr).into());
                    ifindex = if_index(info.ipi6_ifindex);
                }
                cmsg = libc::CMSG_NXTHDR(&hdr, cmsg);
            }
        }
        Some(Inquiry {
            body: buf[..n as usize].to_vec(),
            peer,
            local,
            ifindex,
        })
    }

    /// Sends an answer, from the address the question named when the socket
    /// knows it.
    #[cfg(not(windows))]
    fn send(&self, msg: &[u8], query: &Inquiry) -> std::io::Result<usize> {
        let (addr, addrlen) = sockaddr_for(query.peer);
        let mut iov = libc::iovec {
            iov_base: msg.as_ptr() as *mut libc::c_void,
            iov_len: msg.len(),
        };
        let mut ctl = [0u8; CONTROL_BYTES];
        let mut hdr = libc::msghdr {
            msg_name: &addr as *const libc::sockaddr_storage as *mut libc::c_void,
            msg_namelen: addrlen,
            msg_iov: &mut iov,
            msg_iovlen: 1,
            msg_control: ctl.as_mut_ptr() as *mut libc::c_void,
            msg_controllen: 0,
            msg_flags: 0,
        };
        let space = match (self.v6, query.local) {
            (false, Some(IpAddr::V4(ip))) => {
                let space = unsafe { libc::CMSG_SPACE(std::mem::size_of::<libc::in_pktinfo>() as u32) };
                hdr.msg_controllen = space as usize;
                let cmsg = match unsafe { libc::CMSG_FIRSTHDR(&hdr) } {
                    cmsg if cmsg.is_null() => return Err(last_error()),
                    cmsg => cmsg,
                };
                unsafe {
                    (*cmsg).cmsg_level = libc::SOL_IP;
                    (*cmsg).cmsg_type = libc::IP_PKTINFO;
                    (*cmsg).cmsg_len =
                        libc::CMSG_LEN(std::mem::size_of::<libc::in_pktinfo>() as u32) as usize;
                    let info = &mut *(libc::CMSG_DATA(cmsg) as *mut libc::in_pktinfo);
                    // `ipi_spec_dst` is the send-side name for the source address.
                    info.ipi_ifindex = 0;
                    info.ipi_spec_dst = libc::in_addr { s_addr: u32::from_le_bytes(ip.octets()) };
                    info.ipi_addr = libc::in_addr { s_addr: 0 };
                }
                true
            }
            (true, Some(IpAddr::V6(ip))) => {
                let space = unsafe { libc::CMSG_SPACE(std::mem::size_of::<libc::in6_pktinfo>() as u32) };
                hdr.msg_controllen = space as usize;
                let cmsg = match unsafe { libc::CMSG_FIRSTHDR(&hdr) } {
                    cmsg if cmsg.is_null() => return Err(last_error()),
                    cmsg => cmsg,
                };
                unsafe {
                    (*cmsg).cmsg_level = libc::IPPROTO_IPV6;
                    (*cmsg).cmsg_type = libc::IPV6_PKTINFO;
                    (*cmsg).cmsg_len =
                        libc::CMSG_LEN(std::mem::size_of::<libc::in6_pktinfo>() as u32) as usize;
                    let info = &mut *(libc::CMSG_DATA(cmsg) as *mut libc::in6_pktinfo);
                    info.ipi6_addr = libc::in6_addr { s6_addr: ip.octets() };
                    set_if_index(info, query.ifindex);
                }
                true
            }
            // No address reported, or a peer the socket's family cannot name:
            // the route picks the source, as it did before the redirect rules.
            _ => false,
        };
        let rc = unsafe { libc::sendmsg(self.fd(), &hdr, 0) };
        if rc < 0 {
            // A transparent socket that was not granted the option, or an
            // address the routing table will not source from: the client sees
            // a timeout, so say which half failed instead of failing silently.
            let err = std::io::Error::last_os_error();
            if space {
                crate::logbuf::push(&format!(
                    "proxy: DNS answer from {} refused ({err}); the redirect rules \
                     need AdFree to run as root",
                    query.local.unwrap_or(IpAddr::V4(Ipv4Addr::UNSPECIFIED))
                ));
            }
            return Err(err);
        }
        Ok(rc as usize)
    }
}

impl Listener {
    /// Sends an answer on a socket that was given no ancillary data.
    ///
    /// `sendmsg` is a `-1` stub on Windows, so the shared implementation
    /// answered nothing there; this is the same answer without the packet-info
    /// the platform does not have.
    #[cfg(windows)]
    fn send(&self, msg: &[u8], query: &Inquiry) -> std::io::Result<usize> {
        write_datagram(self.fd(), msg, query.peer)
    }
}

/// One datagram back to the peer it came from, with no ancillary data.
#[cfg_attr(not(windows), allow(dead_code))]
fn write_datagram(fd: RawFd, msg: &[u8], peer: SocketAddr) -> std::io::Result<usize> {
    let (addr, addrlen) = sockaddr_for(peer);
    let n = unsafe {
        libc::sendto(
            fd,
            msg.as_ptr() as *const libc::c_void,
            msg.len(),
            0,
            &addr as *const libc::sockaddr_storage as *const libc::sockaddr,
            addrlen,
        )
    };
    if n < 0 {
        return Err(last_error());
    }
    Ok(n as usize)
}

fn last_error() -> std::io::Error {
    std::io::Error::last_os_error()
}

/// Queries answered at the same time. A page load asks for a dozen hosts at
/// once and a stub resolver batches as many again; the listener is one socket
/// for the whole machine, so a single query held for the 5 s forward timeout
/// would otherwise stall every lookup on the box.
const IN_FLIGHT: usize = 64;

/// Says whether a forwarded datagram can leave without coming back here.
///
/// The redirect layout only loop-proofs itself through the fwmark; if the mark
/// cannot be set (no `CAP_NET_ADMIN`) that is said once, loudly, instead of
/// burning a query storm in silence.
fn announce_mark() {
    let mark_ok = probe_mark();
    MARK_OK.store(mark_ok, Ordering::Relaxed);
    if !mark_ok {
        crate::logbuf::push(
            "proxy: cannot set the firewall mark (needs root); with nft redirect \
             rules installed, DNS forwarding may loop — run AdFree as root or use \
             explicit-proxy mode",
        );
    }
}

/// Answers queries with one blocking reader per socket.
///
/// Windows has no working readiness poll for a datagram socket: `WSAPoll`
/// returns 0 for a socket that has a query waiting in it, so a loop built on it
/// read nothing, ever — the machine resolved no name at all while the tray said
/// it was protected. A socket with a read timeout needs no readiness poll, and a
/// reader per socket means a query is answered the moment it lands.
#[cfg(windows)]
pub(crate) fn serve_loop(listeners: Vec<Listener>, stop: Arc<AtomicBool>) {
    announce_mark();
    let in_flight = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    let mut readers = Vec::new();
    for listener in listeners {
        let stop = Arc::clone(&stop);
        let in_flight = std::sync::Arc::clone(&in_flight);
        let Ok(reader) = std::thread::Builder::new()
            .name("adfree-dns-socket".into())
            .stack_size(512 * 1024)
            .spawn(move || serve_datagrams(listener, stop, in_flight))
        else {
            crate::logbuf::push("proxy: could not start a DNS reader");
            continue;
        };
        readers.push(reader);
    }
    for reader in readers {
        let _ = reader.join();
    }
}

/// One socket's reader: read a datagram, hand it to a worker, repeat.
#[cfg(windows)]
fn serve_datagrams(
    listener: Listener,
    stop: Arc<AtomicBool>,
    in_flight: std::sync::Arc<std::sync::atomic::AtomicUsize>,
) {
    let listener = std::sync::Arc::new(listener);
    let mut buf = vec![0u8; 4096];
    let mut errors = 0u32;
    let mut got = 0u32;
    let started = std::time::Instant::now();
    while !stop.load(Ordering::Relaxed) {
        let query = match listener.socket.recv_from(&mut buf) {
            Ok((n, peer)) => {
                got += 1;
                Inquiry {
                body: buf[..n].to_vec(),
                peer,
                // No packet-info on Windows: the route picks the source address.
                local: None,
                ifindex: 0,
                }
            }
            // A read timeout is the slice this loop wakes on, not a fault.
            Err(e) => {
                errors += 1;
                if errors <= 3 || errors % 300 == 0 {
                    crate::logbuf::push(&format!(
                        "proxy: dns read error #{errors}: {e} (got {got} in {:?})",
                        started.elapsed()
                    ));
                }
                continue;
            }
        };
        if in_flight.fetch_add(1, Ordering::Relaxed) >= IN_FLIGHT {
            in_flight.fetch_sub(1, Ordering::Relaxed);
            static ANNOUNCED: AtomicBool = AtomicBool::new(false);
            if !ANNOUNCED.swap(true, Ordering::Relaxed) {
                crate::logbuf::push(&format!(
                    "proxy: {IN_FLIGHT} DNS queries are still waiting upstream; \
                     dropping new ones until a worker retires"
                ));
            }
            continue;
        }
        // The whole datagram is in `query`; the worker shares the socket only to
        // write the answer back through it, so it gets the same handle as an
        // `Arc` rather than a copied descriptor nobody would close.
        let listener = std::sync::Arc::clone(&listener);
        let in_flight_inner = std::sync::Arc::clone(&in_flight);
        let stop_inner = Arc::clone(&stop);
        let spawned = std::thread::Builder::new()
            .name("adfree-dns-query".into())
            .stack_size(512 * 1024)
            .spawn(move || {
                let _guard = InFlight(in_flight_inner);
                serve_one(&listener, &query, &stop_inner);
            });
        if spawned.is_err() {
            crate::logbuf::push("proxy: could not spawn a DNS answer thread");
        }
    }
}

#[cfg(not(windows))]
pub(crate) fn serve_loop(listeners: Vec<Listener>, stop: Arc<AtomicBool>) {
    announce_mark();
    // One listener socket is shared by every answering thread: `sendto` on a
    // UDP socket is atomic per datagram, and the fd stays open for as long as
    // the loop runs, so the threads need no ownership of it beyond the `Arc`.
    let listeners: Vec<std::sync::Arc<Listener>> =
        listeners.into_iter().map(std::sync::Arc::new).collect();
    let mut fds: Vec<libc::pollfd> = listeners
        .iter()
        .map(|l| libc::pollfd { fd: l.fd(), events: libc::POLLIN, revents: 0 })
        .collect();
    let mut buf = vec![0u8; 4096];
    let in_flight = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
    while !stop.load(Ordering::Relaxed) {
        let rc = unsafe { libc::poll(fds.as_mut_ptr(), fds.len() as libc::nfds_t, POLL_SLICE_MS) };
        if rc <= 0 {
            continue;
        }
        for (index, ready) in fds.iter().enumerate() {
            if ready.revents & libc::POLLIN == 0 {
                continue;
            }
            let Some(query) = listeners[index].recv(&mut buf) else {
                continue;
            };
            // Answering waits on the upstream resolver, so it happens off this
            // loop: a query the upstream leaves unanswered must cost its own
            // client a wait, not every other lookup on the machine.
            let in_flight = std::sync::Arc::clone(&in_flight);
            if in_flight.fetch_add(1, Ordering::Relaxed) >= IN_FLIGHT {
                in_flight.fetch_sub(1, Ordering::Relaxed);
                // Past the cap: silence, and the client's own retry comes back
                // when a worker has retired. Dropping a query is cheaper for
                // the machine than answering it late — but the log has to say
                // so once, or a lookup storm reads as a broken resolver.
                static ANNOUNCED: AtomicBool = AtomicBool::new(false);
                if !ANNOUNCED.swap(true, Ordering::Relaxed) {
                    crate::logbuf::push(&format!(
                        "proxy: {IN_FLIGHT} DNS queries are still waiting upstream; \
                         dropping new ones until a worker retires"
                    ));
                }
                continue;
            }
            let listener = std::sync::Arc::clone(&listeners[index]);
            let stop = Arc::clone(&stop);
            let spawned = std::thread::Builder::new()
                .name("adfree-dns-query".into())
                .stack_size(512 * 1024)
                .spawn(move || {
                    let _guard = InFlight(in_flight);
                    serve_one(&listener, &query, &stop);
                });
            if spawned.is_err() {
                crate::logbuf::push("proxy: could not spawn a DNS answer thread");
            }
        }
    }
}

/// Returns the in-flight slot when the answering thread ends, however it ends.
struct InFlight(std::sync::Arc<std::sync::atomic::AtomicUsize>);

impl Drop for InFlight {
    fn drop(&mut self) {
        self.0.fetch_sub(1, Ordering::Relaxed);
    }
}

/// One query: parse, filter, answer or forward.
fn serve_one(listener: &Listener, query: &Inquiry, stop: &AtomicBool) {
    let core = core();
    let client = query.peer;
    // The listener answers on every local address so a redirected query
    // arrives; a query from another machine is not ours to answer, and
    // silence leaves no open resolver on the network.
    if !super::is_local_peer(client) {
        return;
    }
    // A query this listener forwarded coming back: under the redirect rules an
    // unmarked upstream query returns here instead of leaving, and answering it
    // starts another hop. Dropping it is where the loop ends.
    if is_own_forward(&client) {
        static WARNED: AtomicBool = AtomicBool::new(false);
        if !WARNED.swap(true, Ordering::Relaxed) {
            crate::logbuf::push(
                "proxy: a forwarded DNS query came back to the listener; \
                 dropping the loop instead of forwarding it again",
            );
        }
        return;
    }
    let Some(parsed) = dns::parse_query(&query.body) else {
        crate::logbuf::push("proxy: a DNS query could not be parsed and was left alone");
        return; // Not a parseable query: silence, the client retries or gives up.
    };
    if !parsed.is_query() || !parsed.has_question {
        return;
    }
    let stats = core.stats();
    stats.inc(&stats.dns_queries);
    let cfg = core.config();
    let host = parsed.qname.as_str().to_string();
    crate::logbuf::push(&format!("proxy: dns query {host} type={}", parsed.qtype));
    let server_text = core.dns_server_text();
    // A page the user took out of filtering: a query names no page, so the
    // exemption is remembered rather than looked up (see
    // [`crate::page_exception`]). Every query while it holds is resolved by
    // the machine's own resolver, not the filtering provider.
    let plain = core.filter_engine().excepted_page_open();

    // The reply this listener sends, if any, and the log row describing it.
    enum Served {
        Blocked { message: Vec<u8>, rule: String, list: String, category: u8 },
        Rewritten { message: Vec<u8>, answer: String },
        /// An answer with the question in it and no records: the address the
        /// client asked for is one this machine could not use.
        Nodata(Vec<u8>),
        Forwarded(Option<Vec<u8>>),
    }

    // App firewall first, exactly as the tun path orders it: a barred app
    // must not learn whether a host exists.
    let packages = core.apps_for_local_port(client.port());
    let decision = if let Some(reason) = core.app_blocked_reason(&packages) {
        Served::Blocked {
            message: dns::block_message(&parsed, cfg.block_mode).unwrap_or_default(),
            rule: reason.to_string(),
            list: "App firewall".to_string(),
            category: crate::filter::CAT_AD,
        }
    } else if !cfg.dns_protection || core.is_paused() {
        Served::Forwarded(forward_query(&query.body, stop, plain))
    } else if matches!(parsed.qtype, dns::TYPE_A | dns::TYPE_AAAA)
        && core.dns_rewrite_for(&host).is_some()
    {
        // A user rewrite answers the query here — explicit intent, before
        // the lists, and only for the family it names.
        let address = core.dns_rewrite_for(&host).expect("checked");
        match dns::answer_message(&parsed, &[address], 60) {
            Some(message) => Served::Rewritten { message, answer: address.to_string() },
            None => Served::Forwarded(forward_query(&query.body, stop, plain)),
        }
    } else {
        let filter = core.filter_engine();
        let mut verdict = filter.check_host(&host);
        if verdict.blocked && verdict.rule_text.is_none() {
            verdict = filter.check_host_detailed(&host);
        }
        // A host a `$redirect` answers — or one a stub resource is swapped
        // into, which the rule can only say with a path — is not refused here.
        // The answer is a resource the HTTPS path serves, and only that path
        // sees the request line the rule is written against. Without TLS
        // termination there is nothing to serve it, so such a host is
        // sinkholed as usual rather than let through unanswered.
        let deferred = verdict.redirect && core.mitm_ready(&cfg);
        // A page the user took out of filtering: a query names no page, so the
        // exemption is remembered rather than looked up, and the resolver
        // stands down while it holds (see [`crate::page_exception`]).
        if verdict.blocked && !deferred && !filter.excepted_page_open() {
            Served::Blocked {
                message: dns::block_message(&parsed, cfg.block_mode).unwrap_or_default(),
                rule: verdict.rule_text.unwrap_or_default(),
                list: verdict.list_name.unwrap_or_default(),
                category: verdict.category,
            }
        } else if parsed.qtype == dns::TYPE_AAAA && super::egress::withhold_aaaa() {
            // The proxy has just failed to dial the kind of address this
            // answer would name, so the machine is not carrying IPv6. An empty
            // answer sends the client to `A` in the same round trip; handing
            // the record through would buy a connection the redirect catches
            // and the proxy cannot finish — see [`super::egress`].
            Served::Nodata(dns::nodata_message(&parsed).unwrap_or_default())
        } else {
            Served::Forwarded(forward_query(&query.body, stop, plain))
        }
    };

    // The owner every row this listener writes names, taken once rather than
    // re-derived in each arm.
    let app = packages.first().map(String::as_str).unwrap_or("");

    // An answer fetched while the page is excepted carries no cache life: the
    // browser must ask again as soon as the exception lifts, and the resolver
    // then refuses the host it had been allowed to reach.
    let decision = match decision {
        Served::Forwarded(Some(mut message)) if plain => {
            dns::clamp_answer_ttl(&mut message, 0);
            Served::Forwarded(Some(message))
        }
        other => other,
    };

    crate::logbuf::push(&format!(
        "proxy: dns served {} kind={}",
        host,
        match &decision {
            Served::Blocked { .. } => "blocked",
            Served::Rewritten { .. } => "rewritten",
            Served::Nodata(_) => "nodata",
            Served::Forwarded(None) => "forwarded-none",
            Served::Forwarded(Some(_)) => "forwarded-answer",
        }
    ));
    match decision {
        Served::Blocked { message, rule, list, category } => {
            if message.is_empty() {
                return;
            }
            stats.inc(&stats.dns_blocked);
            stats.add(&stats.bytes_saved, BYTES_PER_BLOCKED_REQUEST);
            // One send, and the log says so when it fails: a second call here
            // would send the same answer twice and count it once.
            if let Err(e) = listener.send(&message, query) {
                crate::logbuf::push_error(&format!("proxy: dns answer not sent: {e}"));
            } else {
                stats.inc(&stats.dns_replies);
            }
            core.log_event(row(
                crate::events::block_event(
                    crate::events::ACTION_BLOCK,
                    &host,
                    &server_text,
                    &rule,
                    &list,
                    category,
                    0,
                    BYTES_PER_BLOCKED_REQUEST as u32,
                    false,
                ),
                app,
            ));
        }
        Served::Rewritten { message, answer } => {
            stats.inc(&stats.dns_allowed);
            if listener.send(&message, query).is_ok() {
                stats.inc(&stats.dns_replies);
                let mut event = row(crate::events::allow_event(&host, &server_text, 0), app);
                event = crate::events::with_answer(event, &answer);
                core.log_event(event);
            }
        }
        Served::Nodata(message) => {
            if message.is_empty() {
                return;
            }
            // Counted as an allowed reply: the lookup went through, it just
            // named nothing.
            stats.inc(&stats.dns_allowed);
            if listener.send(&message, query).is_ok() {
                stats.inc(&stats.dns_replies);
                // An empty answer is still an answer the log shows — this is
                // the record of a family the machine could not reach, which is
                // otherwise invisible. The switch decides it like any other
                // allowed row.
                if cfg.log_allowed {
                    core.log_event(row(
                        crate::events::allow_event(&host, &server_text, 0),
                        app,
                    ));
                }
            }
        }
        Served::Forwarded(reply) => match reply {
            Some(message) => {
                stats.inc(&stats.dns_allowed);
                let answered = match listener.send(&message, query) {
                    Ok(_) => true,
                    Err(e) => {
                        crate::logbuf::push_error(&format!("proxy: dns answer not sent: {e}"));
                        false
                    }
                };
                if answered {
                    stats.inc(&stats.dns_replies);
                }
                // AdGuard's DNS tab lists every lookup it saw, refused ones
                // and answered ones, with the addresses the answer carried.
                // The switch in the settings decides whether we keep those
                // rows, exactly as the tun path does.
                if answered && cfg.log_allowed {
                    let mut event = row(crate::events::allow_event(&host, &server_text, 0), app);
                    event = crate::events::with_traffic(event, 0, message.len() as u32);
                    let answer = answer_text(&message);
                    if !answer.is_empty() {
                        event = crate::events::with_answer(event, &answer);
                    }
                    core.log_event(event);
                }
            }
            None => {
                stats.inc(&stats.dns_timeouts);
            }
        },
    }
}

/// Every row the DNS listener writes is the same shape: a lookup, on the
/// transport it actually arrived on, classified as DNS, owned by the app whose
/// socket asked. Call sites only hand over the decision.
fn row(event: crate::events::Event, app: &str) -> crate::events::Event {
    crate::events::with_protocol(
        crate::events::with_type(crate::events::with_app(event, app), crate::events::req_type::DNS),
        crate::events::protocol::DNS_UDP,
    )
}

/// The addresses an answer carried, in the form the log's answer column shows:
/// the same four the tun path keeps, newest first.
fn answer_text(message: &[u8]) -> String {
    dns::answer_addresses(message)
        .into_iter()
        .take(4)
        .map(|(address, _)| address.to_string())
        .collect::<Vec<_>>()
        .join(", ")
}

/// Sends the query to the engine's resolver (or `/etc/resolv.conf` when no
/// provider was configured) and waits for the answer on the same marked
/// socket. `None` means the upstream did not answer in time.
///
/// `plain` asks the machine's own resolver instead. A page the user took out
/// of filtering is answered by it: the provider this app was pointed at is
/// usually a filtering one, and its blocklist would answer 0.0.0.0 for
/// exactly the hosts the exception exists to let through.
/// Marks a forwarding socket's port as ours for as long as it is alive.
struct ForwardPort(u16);

impl ForwardPort {
    fn new(port: u16) -> Self {
        if let Ok(mut slot) = FORWARD_PORTS.lock() {
            slot.get_or_insert_with(HashSet::new).insert(port);
        }
        ForwardPort(port)
    }
}

impl Drop for ForwardPort {
    fn drop(&mut self) {
        if let Ok(mut slot) = FORWARD_PORTS.lock() {
            if let Some(ports) = slot.as_mut() {
                ports.remove(&self.0);
            }
        }
    }
}

/// Binds a forwarding socket inside the range the redirect skips.
///
/// The range is small on purpose (a process cannot ask for a thousand ports
/// without taking them from someone), and a retry over it costs nothing a query
/// has not already spent on the network.
#[cfg(windows)]
fn bind_own_port(family: SocketAddr) -> std::io::Result<UdpSocket> {
    let (lo, hi) = crate::sys::windivert_filter::OWN_PORTS;
    let mut last = std::io::Error::other("no port in the redirect's own range is free");
    for port in lo..=hi {
        let addr = SocketAddr::new(family.ip(), port);
        match UdpSocket::bind(addr) {
            Ok(socket) => return Ok(socket),
            Err(e) => last = e,
        }
    }
    Err(last)
}

/// Whether a query arrived from this process's own forwarding socket.
fn is_own_forward(peer: &SocketAddr) -> bool {
    if !peer.ip().is_loopback() {
        return false;
    }
    FORWARD_PORTS
        .lock()
        .ok()
        .and_then(|slot| slot.as_ref().map(|ports| ports.contains(&peer.port())))
        .unwrap_or(false)
}

fn forward_query(body: &[u8], stop: &AtomicBool, plain: bool) -> Option<Vec<u8>> {
    let started = std::time::Instant::now();
    let left = || FORWARD_TIMEOUT_MS - started.elapsed().as_millis() as i32;
    let candidates = forward_targets(plain);
    if candidates.is_empty() {
        return None;
    }
    // Unmarked, a datagram to port 53 is redirected by the same rule that
    // delivered the client's query: it would come back here and never reach a
    // resolver. Port 53 over TCP is not in those rules.
    let udp = MARK_OK.load(Ordering::Relaxed);
    if udp {
        for (index, resolver) in candidates.iter().enumerate() {
            let remaining = left();
            if remaining <= 0 {
                break;
            }
            // Split what is left over the servers not yet tried, so a dead
            // primary cannot starve the second opinion behind it.
            let share = remaining / (candidates.len() - index) as i32;
            if let Some(reply) = forward_over_udp(body, *resolver, stop, share.max(ATTEMPT_MS)) {
                return Some(reply);
            }
        }
    }
    // Every server dropped every copy, or this process cannot mark a datagram:
    // the connection-oriented framing is the one path left.
    for resolver in &candidates {
        let remaining = left();
        if remaining <= 0 {
            return None;
        }
        if let Some(reply) = forward_over_tcp(body, *resolver, remaining) {
            return Some(reply);
        }
    }
    None
}

/// The servers a query may be sent to, in the order they are tried: the
/// configured one, then the machine's own, which is the second opinion a
/// filtered or lossy upstream needs.
fn forward_targets(plain: bool) -> Vec<SocketAddr> {
    let mut targets = Vec::new();
    let primary = if plain {
        system_resolver().or_else(resolver_addr)
    } else {
        resolver_addr()
    };
    if let Some(primary) = primary {
        targets.push(primary);
    }
    if let Some(system) = system_resolver() {
        if !targets.contains(&system) {
            targets.push(system);
        }
    }
    targets
}

/// One query over UDP, with a resend inside `budget_ms` when the first copy is
/// not answered.
fn forward_over_udp(
    body: &[u8],
    resolver: SocketAddr,
    stop: &AtomicBool,
    budget_ms: i32,
) -> Option<Vec<u8>> {
    let any: SocketAddr = if resolver.is_ipv4() {
        (Ipv4Addr::UNSPECIFIED, 0).into()
    } else {
        (Ipv6Addr::UNSPECIFIED, 0).into()
    };
    // Windows has no firewall mark to say "this datagram is mine", so the
    // redirect's own-port range is the only loop proof there: a socket bound
    // outside it has its query sent straight back to this listener, which then
    // asks again, forever.
    #[cfg(windows)]
    let socket = bind_own_port(any).ok()?;
    #[cfg(not(windows))]
    let socket = UdpSocket::bind(any).ok()?;
    // Registered before the first byte leaves: the redirect can turn the query
    // around faster than the next line runs.
    let _port = ForwardPort::new(socket.local_addr().ok()?.port());
    try_mark(socket.as_raw_fd());
    let fd = socket.as_raw_fd();
    let started = std::time::Instant::now();
    let mut reply = vec![0u8; 4096];
    let mut attempt = 0u32;
    loop {
        let elapsed = started.elapsed().as_millis() as i32;
        if stop.load(Ordering::Relaxed) || elapsed >= budget_ms {
            return None;
        }
        // Halfway through the budget with nothing back: send the same query
        // again, up to ATTEMPT_MS apart, rather than wait out the whole window
        // on a datagram that was dropped.
        if elapsed >= (attempt + 1) as i32 * ATTEMPT_MS {
            attempt += 1;
            socket.send_to(body, resolver).ok()?;
        }
        if !poll_readable(fd, POLL_SLICE_MS) {
            continue;
        }
        match socket.recv(&mut reply) {
            Ok(n) if n >= 12 => {
                let mut out = reply[..n].to_vec();
                // The transaction id is the client's own (we forwarded the
                // bytes untouched), but EDNS may have been trimmed upstream;
                // hand back exactly what arrived.
                dns::patch_txid(&mut out, dns::parse_query(body).map(|q| q.id).unwrap_or(0));
                return Some(out);
            }
            Ok(_) => return None,
            Err(_) => return None,
        }
    }
}

/// One query over DNS-over-TCP (RFC 1035 4.2.2): a two-byte length, the
/// message, and the same framing back.
///
/// The redirect rules cover UDP port 53 only, so this path is open to a process
/// that cannot mark its sockets. It costs a connection per answer, which is the
/// right trade for a case that only arises when the mark is unavailable.
fn forward_over_tcp(body: &[u8], resolver: SocketAddr, budget_ms: i32) -> Option<Vec<u8>> {
    let budget = std::time::Duration::from_millis(budget_ms.max(250) as u64);
    let mut stream = TcpStream::connect_timeout(&resolver, budget).ok()?;
    stream.set_read_timeout(Some(budget)).ok()?;
    stream.set_write_timeout(Some(budget)).ok()?;
    let len = u16::try_from(body.len()).ok()?;
    let mut framed = Vec::with_capacity(body.len() + 2);
    framed.extend_from_slice(&len.to_be_bytes());
    framed.extend_from_slice(body);
    stream.write_all(&framed).ok()?;
    let mut header = [0u8; 2];
    stream.read_exact(&mut header).ok()?;
    let mut reply = vec![0u8; u16::from_be_bytes(header) as usize];
    stream.read_exact(&mut reply).ok()?;
    if reply.len() < 12 {
        return None;
    }
    dns::patch_txid(&mut reply, dns::parse_query(body).map(|q| q.id).unwrap_or(0));
    Some(reply)
}

/// Resolves one name the way an excepted page is resolved: over the marked
/// socket to the machine's own resolver, never through the local stub.
///
/// The proxy has to turn a `CONNECT host:port` into an address itself, and the
/// obvious way — `getaddrinfo` — asks `systemd-resolved`, whose answer is
/// whatever it cached, including a filtering upstream's `0.0.0.0` from before
/// the page was excepted. A page the user took out of filtering must not be
/// dialled at a sinkhole the exception exists to lift.
pub(crate) fn resolve_plainly(host: &str) -> Option<std::net::IpAddr> {
    let query = dns::build_query(host, dns::TYPE_A, 0x7a7a)?;
    let reply = forward_query(&query, &AtomicBool::new(false), true)?;
    dns::answer_addresses(&reply)
        .into_iter()
        .map(|(address, _)| address)
        .find(|address| address.is_ipv4())
}

/// The upstream resolver: the engine's configured one, else the system's.
fn resolver_addr() -> Option<SocketAddr> {
    let configured = core().resolver_addr();
    if configured.ip().is_unspecified() {
        return system_resolver();
    }
    if configured.ip().is_loopback() {
        // A stub on loopback is not a way out of this listener: under the
        // redirect rules the stub's own upstream queries come back here, and
        // forwarding into it answers our own request. The real server is
        // readable from the files the stub publishes.
        if let Some(system) = system_resolver() {
            if system != configured {
                return Some(system);
            }
        }
        return None;
    }
    Some(configured)
}

/// Where a query can actually leave the machine: the first non-loopback
/// `nameserver` in the resolver files, in the order a desktop would trust
/// them. `/etc/resolv.conf` on a `systemd-resolved` or NetworkManager host
/// names only the stub (127.0.0.53), and the stub answers from the real
/// servers it lists in its own file — which is the one hop that cannot loop.
#[cfg(not(windows))]
pub(crate) fn system_resolver() -> Option<SocketAddr> {
    [
        "/etc/resolv.conf",
        "/run/systemd/resolve/resolv.conf",
        "/run/NetworkManager/resolv.conf",
    ]
    .into_iter()
    .find_map(first_real_nameserver)
}

/// Where a query can actually leave the machine, on Windows: the servers the
/// adapters were given.
///
/// The Linux answer is a file listing; Windows has none, and a listener with no
/// upstream answers nothing at all. That is what this machine did: the tray said
/// protected, every lookup came back "no such host", and the browser drew a page
/// that could not be reached — a DNS filter that had no way to ask anyone.
#[cfg(windows)]
pub(crate) fn system_resolver() -> Option<SocketAddr> {
    use windows_sys::Win32::NetworkManagement::IpHelper::{GetNetworkParams, FIXED_INFO_W2KSP1};
    let mut size: u32 = 0;
    unsafe { GetNetworkParams(std::ptr::null_mut(), &mut size) };
    if size == 0 {
        return None;
    }
    let mut buf = vec![0u8; size as usize];
    let rc = unsafe { GetNetworkParams(buf.as_mut_ptr() as *mut FIXED_INFO_W2KSP1, &mut size) };
    if rc != 0 {
        return None;
    }
    let info = unsafe { &*(buf.as_ptr() as *const FIXED_INFO_W2KSP1) };
    let mut node: *const _ = &info.DnsServerList;
    while !node.is_null() {
        let entry = unsafe { &*node };
        let text = unsafe { std::ffi::CStr::from_ptr(entry.IpAddress.String.as_ptr() as *const i8) };
        if let Ok(text) = text.to_str() {
            if let Ok(ip) = text.trim().parse::<IpAddr>() {
                // A stub on loopback is the thing we are trying to get upstream
                // of, and an empty entry means "no server on this adapter".
                if !ip.is_loopback() && !ip.is_unspecified() {
                    return Some(SocketAddr::new(ip, 53));
                }
            }
        }
        node = entry.Next;
    }
    None
}

#[cfg(not(windows))]
fn first_real_nameserver(path: &str) -> Option<SocketAddr> {
    let text = std::fs::read_to_string(path).ok()?;
    for line in text.lines() {
        let mut fields = line.split_whitespace();
        if fields.next() != Some("nameserver") {
            continue;
        }
        if let Ok(ip) = fields.next()?.parse::<IpAddr>() {
            // Link-local and loopback are not an exit; a stub especially is
            // the thing we are trying to get upstream of.
            if ip.is_loopback() || ip.is_unspecified() {
                continue;
            }
            return Some(SocketAddr::new(ip, 53));
        }
    }
    None
}

/// The interface index an IPv6 datagram arrived on. bionic types the field
/// `c_int` and every other libc `u32`, so the value crosses through here
/// rather than at each use.
#[cfg(target_os = "android")]
#[cfg_attr(windows, allow(dead_code))]
fn if_index(value: libc::c_int) -> u32 {
    value as u32
}

#[cfg(not(target_os = "android"))]
#[cfg_attr(windows, allow(dead_code))]
fn if_index(value: u32) -> u32 {
    value
}

#[cfg(target_os = "android")]
#[cfg_attr(windows, allow(dead_code))]
fn set_if_index(info: &mut libc::in6_pktinfo, value: u32) {
    info.ipi6_ifindex = value as libc::c_int;
}

#[cfg(not(target_os = "android"))]
#[cfg_attr(windows, allow(dead_code))]
fn set_if_index(info: &mut libc::in6_pktinfo, value: u32) {
    info.ipi6_ifindex = value;
}

/// True when a fresh socket can carry the firewall mark.
fn probe_mark() -> bool {
    // Windows has no `SO_MARK` to set: the loop proof there is the source-port
    // range the redirect filter skips, and binding into it asks no privilege of
    // anyone. There is nothing that can fail, so there is nothing to warn about
    // — "run AdFree as root" is advice this platform cannot take.
    #[cfg(windows)]
    let ok = true;

    #[cfg(not(windows))]
    let ok = {
        probe_mark_unix()
    };

    ok
}

#[cfg(not(windows))]
fn probe_mark_unix() -> bool {
    let Ok(socket) = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)) else {
        return false;
    };
    let fd = socket.as_raw_fd();
    let mark = super::FWMARK;
    let rc = unsafe {
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            libc::SO_MARK,
            &mark as *const u32 as *const libc::c_void,
            std::mem::size_of::<u32>() as libc::socklen_t,
        )
    };
    rc == 0
}

#[cfg(test)]
mod tests {
    use super::*;

    fn query_bytes(name: &str, qtype: u16) -> Vec<u8> {
        dns::build_query(name, qtype, 0xbeef).unwrap()
    }

    #[test]
    fn block_message_answers_the_query_with_the_chosen_refusal() {
        let body = query_bytes("ads.example.com", dns::TYPE_A);
        let q = dns::parse_query(&body).unwrap();
        let msg = dns::block_message(&q, dns::BlockMode::NxDomain).unwrap();
        assert_eq!(&msg[0..2], &0xbeefu16.to_be_bytes(), "the id is echoed");
        assert_eq!(msg[2] & 0x80, 0x80, "QR is set");
        assert_eq!((msg[3] & 0x0f), 3, "NXDOMAIN");
        // A second parse of the answer proves it is wire-valid.
        let a = dns::parse_query(&msg).unwrap();
        assert!(!a.is_query());
        assert_eq!(a.qname.as_str(), "ads.example.com");
    }

    /// "Turn filtering off for this page" has to reach a resolver the client
    /// will actually ask again. A refusal cached for a minute keeps the page
    /// failing on the browser's own memory of it, with the exception in place
    /// and no query left to answer correctly. So nothing a refusal says may
    /// outlive the moment it was said.
    #[test]
    fn a_refusal_is_not_worth_caching() {
        let body = query_bytes("ads.example.com", dns::TYPE_A);
        let q = dns::parse_query(&body).unwrap();
        let ttl_at = q.q_end + 6;

        let zero = dns::block_message(&q, dns::BlockMode::ZeroIp).unwrap();
        assert_eq!(
            u32::from_be_bytes([zero[ttl_at], zero[ttl_at + 1], zero[ttl_at + 2], zero[ttl_at + 3]]),
            0,
            "the 0.0.0.0 record must not be cached"
        );

        let nx = dns::block_message(&q, dns::BlockMode::NxDomain).unwrap();
        assert_eq!(&nx[8..10], &1u16.to_be_bytes(), "an SOA is offered");
        assert_eq!(
            u32::from_be_bytes([nx[ttl_at], nx[ttl_at + 1], nx[ttl_at + 2], nx[ttl_at + 3]]),
            0,
            "the SOA must not be cached"
        );
        let minimum = nx.len() - 4;
        assert_eq!(
            u32::from_be_bytes([nx[minimum], nx[minimum + 1], nx[minimum + 2], nx[minimum + 3]]),
            0,
            "and its minimum is the negative TTL"
        );
    }

    #[test]
    fn zero_ip_mode_carries_a_record() {
        let body = query_bytes("track.example.com", dns::TYPE_A);
        let q = dns::parse_query(&body).unwrap();
        let msg = dns::block_message(&q, dns::BlockMode::ZeroIp).unwrap();
        assert_eq!(&msg[6..8], &1u16.to_be_bytes(), "one answer record");
        assert!(msg.ends_with(&[0, 0, 0, 0]), "the record is 0.0.0.0");
    }

    /// A row is only worth reading when its transport is something the engine
    /// recorded rather than something the UI guesses from the action, so every
    /// row this listener writes says DNS over UDP and names the app that asked.
    #[test]
    fn the_dns_rows_name_the_transport_they_arrived_on() {
        let event = row(
            crate::events::allow_event("ads.example.com", "1.1.1.1", 0),
            "com.example.browser",
        );
        assert_eq!(event.protocol(), crate::events::protocol::DNS_UDP);
        assert_eq!(event.req_type_name(), "dns");
        assert_eq!(event.app(), "com.example.browser");
        assert_eq!(event.action, crate::events::ACTION_ALLOW);
    }

    #[test]
    fn the_answer_column_keeps_the_addresses_the_reply_carried() {
        let body = query_bytes("multi.example.com", dns::TYPE_A);
        let q = dns::parse_query(&body).unwrap();
        let one: IpAddr = "93.184.216.34".parse().unwrap();
        let two: IpAddr = "104.20.22.33".parse().unwrap();
        let msg = dns::answer_message(&q, &[one, two], 60).unwrap();
        assert_eq!(answer_text(&msg), "93.184.216.34, 104.20.22.33");
        // A refusal echoes the question and carries no record: nothing to name.
        let refused = dns::block_message(&q, dns::BlockMode::NxDomain).unwrap();
        assert_eq!(answer_text(&refused), "");
    }

    #[test]
    fn answer_message_answers_only_the_asked_family() {
        let body = query_bytes("v6.example.com", dns::TYPE_AAAA);
        let q = dns::parse_query(&body).unwrap();
        let v4: IpAddr = "93.184.216.34".parse().unwrap();
        assert!(
            dns::answer_message(&q, &[v4], 60).is_none(),
            "an A answer never answers an AAAA question"
        );
    }

    #[test]
    fn resolv_conf_names_a_usable_server() {
        // The container may have no nameserver line at all; either answer is
        // fine as long as the parser never panics on what the host wrote.
        let _ = system_resolver();
    }

    /// The port registry is what tells a loop from a client: a forwarding
    /// socket's port is ours only while the socket lives.
    /// The listener's Windows write path, exercised where it can be.
    ///
    /// On Windows a datagram is read with `recv_from` and written back with
    /// `sendto`: there is no ancillary data, and the readiness poll the shared
    /// loop used does not report a readable datagram socket at all — a machine
    /// whose resolver answered nothing, which is what a protected Windows box
    /// did while the tray said it was protected. This test binds two real
    /// sockets and round-trips a query and its answer through the calls the
    /// Windows listener makes, so a change that stops them working fails here
    /// instead of only on a machine nobody is watching.
    #[test]
    fn a_query_goes_out_and_its_answer_comes_back_through_the_windows_calls() {
        let server = UdpSocket::bind("127.0.0.1:0").expect("server socket");
        let client = UdpSocket::bind("127.0.0.1:0").expect("client socket");
        let server_addr = server.local_addr().expect("server addr");
        let client_addr = client.local_addr().expect("client addr");
        for socket in [&server, &client] {
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(2)))
                .expect("timeout");
        }
        let query = b"\x12\x34\x01\x00\x00\x01\x00\x00\x00\x00\x00\x00";
        assert_eq!(
            write_datagram(client.as_raw_fd(), query, server_addr).expect("send"),
            query.len()
        );

        let mut buf = [0u8; 512];
        let (n, peer) = server.recv_from(&mut buf).expect("a datagram arrives");
        assert_eq!(&buf[..n], query);
        assert_eq!(peer, client_addr);

        // The answer goes back the way the listener sends it: `sendto` to the
        // peer the kernel reported, with no packet-info to name a source.
        let answer = b"\x12\x34\x81\x80\x00\x01\x00\x01";
        assert_eq!(
            write_datagram(server.as_raw_fd(), answer, peer).expect("answer"),
            answer.len()
        );
        let mut back = [0u8; 512];
        let (n, from) = client.recv_from(&mut back).expect("the answer arrives");
        assert_eq!(&back[..n], answer);
        assert_eq!(from, server_addr);
    }

    #[test]
    fn a_forwarding_port_is_ours_only_while_it_exists() {
        let socket = UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind");
        let port = socket.local_addr().expect("addr").port();
        let peer = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), port);
        assert!(!is_own_forward(&peer), "nothing registered yet");
        let guard = ForwardPort::new(port);
        assert!(is_own_forward(&peer));
        // An address that is not this machine's is never our own loop, whatever
        // port it names.
        assert!(!is_own_forward(&SocketAddr::new(
            IpAddr::V4(Ipv4Addr::new(192, 0, 2, 9)),
            port
        )));
        drop(guard);
        assert!(!is_own_forward(&peer), "the port went with the socket");
    }

    /// A forwarded query framed over TCP comes back as a DNS message, which is
    /// the path an unprivileged run uses.
    #[test]
    fn a_query_over_tcp_comes_back_patched() {
        use std::net::TcpListener;
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind");
        let address = listener.local_addr().expect("addr");
        let server = std::thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept");
            let mut len = [0u8; 2];
            stream.read_exact(&mut len).expect("length");
            let mut query = vec![0u8; u16::from_be_bytes(len) as usize];
            stream.read_exact(&mut query).expect("query");
            // Answer with the question echoed under the server's own id.
            let mut reply = crate::dns::build_query("example.com", crate::dns::TYPE_A, 0x1234)
                .expect("reply");
            reply[2] |= 0x80; // QR
            let mut framed = (reply.len() as u16).to_be_bytes().to_vec();
            framed.extend_from_slice(&reply);
            stream.write_all(&framed).expect("write");
            query.len()
        });
        let query = crate::dns::build_query("example.com", crate::dns::TYPE_A, 0x4242).expect("query");
        let reply = forward_over_tcp(&query, address, FORWARD_TIMEOUT_MS).expect("answer");
        let parsed = crate::dns::parse_query(&reply).expect("parse");
        assert_eq!(parsed.id, 0x4242, "the client's own id rides back");
        assert!(!parsed.is_query(), "an answer, not a query");
        assert_eq!(server.join().expect("thread"), query.len());
    }

    #[test]
    fn a_loopback_stub_is_never_the_way_out() {
        let dir = std::env::temp_dir().join(format!("adfree-resolv-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        let stub = dir.join("stub");
        std::fs::write(&stub, "# Generated by NetworkManager\nnameserver 127.0.0.53\n").unwrap();
        assert_eq!(
            first_real_nameserver(stub.to_str().unwrap()),
            None,
            "forwarding into the stub is the loop the redirect rules would close on us"
        );
        let real = dir.join("real");
        std::fs::write(&real, "nameserver 10.2.0.1\nnameserver 8.8.8.8\n").unwrap();
        assert_eq!(
            first_real_nameserver(real.to_str().unwrap()),
            Some("10.2.0.1:53".parse().unwrap()),
            "the first real server answers"
        );
        let blank = dir.join("blank");
        std::fs::write(&blank, "options edns0\nnameserver\n").unwrap();
        assert_eq!(first_real_nameserver(blank.to_str().unwrap()), None);
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Both families have to come up on one port. `IPV6_V6ONLY` decides
    /// whether the `::` socket also claims the v4 half, and Linux defaults to
    /// `bindv6only=0` — so setting it *after* the bind is setting it after the
    /// kernel has already answered the question. The v6 bind then collides with
    /// the v4 listener that is already holding the port, `bind_listeners`
    /// swallows the error as "this machine has no IPv6", and every IPv6 query
    /// goes unanswered on a machine that has IPv6 just fine.
    #[test]
    fn both_families_bind_on_the_same_port() {
        let listeners = bind_listeners(free_port()).expect("the DNS listeners");
        assert!(
            listeners.iter().any(|l| !l.v6),
            "the v4 listener is required"
        );
        // A machine without a v6 stack legitimately has one listener; a
        // machine with one must not be silently reduced to the same shape.
        if std::net::UdpSocket::bind((Ipv6Addr::UNSPECIFIED, 0)).is_ok() {
            assert!(
                listeners.iter().any(|l| l.v6),
                "this machine has IPv6, so the v6 listener must be up: {} listeners",
                listeners.len()
            );
        }
    }

    #[test]
    fn a_query_carries_the_address_it_was_sent_to() {
        // The redirect layout depends on reading the destination back, so the
        // socket must report it: bind on a free port, ask that port by name,
        // and see the same address on the other side of `recv`.
        let listeners = bind_listeners(free_port()).unwrap();
        let index = listeners
            .iter()
            .position(|l| !l.v6)
            .expect("a v4 listener");
        let fd = listeners[index].fd();
        let target: SocketAddr = listeners[index]
            .socket
            .local_addr()
            .expect("the bound address");
        let client = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).unwrap();
        client
            .send_to(&query_bytes("probe.example", dns::TYPE_A), target)
            .unwrap();
        let mut buf = vec![0u8; 4096];
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        let query = loop {
            if std::time::Instant::now() > deadline {
                panic!("the listener never saw the query");
            }
            if poll_readable(fd, POLL_SLICE_MS) {
                if let Some(query) = listeners[index].recv(&mut buf) {
                    break query;
                }
            }
        };
        assert_eq!(
            query.local,
            Some(IpAddr::V4(Ipv4Addr::LOCALHOST)),
            "packet-info names the destination the client aimed at"
        );
        assert!(query.peer.port() > 0, "the sender is known too");
    }

    fn free_port() -> u16 {
        let probe = std::net::UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).unwrap();
        probe.local_addr().unwrap().port()
    }

    /// Not part of the normal run: this dials IPv6 for real and asks the real
    /// upstream, so it is `#[ignore]`d. It is the only way to watch the two
    /// halves meet — the proxy's own failed dials turning into an empty `AAAA`
    /// answer from a listener that is actually running:
    ///
    /// ```text
    /// cargo test --lib -- proxy::dns::tests::a_route -- --ignored --nocapture
    /// ```
    #[test]
    #[ignore = "dials IPv6 for real and needs the network"]
    fn a_route_that_cannot_carry_ipv6_turns_aaaa_answers_empty() {
        use std::time::{Duration, Instant};

        let host = "one.one.one.one"; // both families, and it answers
        let port = free_port();
        let listeners = bind_listeners(port).expect("the DNS listeners");
        let stop = Arc::new(AtomicBool::new(false));
        let worker_stop = Arc::clone(&stop);
        let worker = std::thread::spawn(move || serve_loop(listeners, worker_stop));

        let ask = |qtype: u16| -> (u16, usize) {
            let body = dns::build_query(host, qtype, 0x1234).expect("query");
            let client = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)).expect("client socket");
            client
                .send_to(&body, SocketAddr::from((Ipv4Addr::LOCALHOST, port)))
                .expect("send");
            let mut buf = vec![0u8; 4096];
            let deadline = Instant::now() + Duration::from_secs(15);
            loop {
                if Instant::now() > deadline {
                    panic!(
                        "the listener never answered qtype={qtype}; can this machine reach \
                         `/run/systemd/resolve/resolv.conf`'s nameserver at all?"
                    );
                }
                if poll_readable(client.as_raw_fd(), POLL_SLICE_MS) {
                    if let Ok(n) = client.recv(&mut buf) {
                        if n < 12 {
                            panic!("the listener answered with less than a header");
                        }
                        return ((buf[3] & 0x0f) as u16, u16::from_be_bytes([buf[6], buf[7]]) as usize);
                    }
                }
            }
        };

        let (rcode, records) = ask(dns::TYPE_AAAA);
        println!("AAAA before the dials: rcode={rcode} records={records}");
        assert_eq!(rcode, 0);
        assert!(records > 0, "the harness must be able to see records at all");

        // Three dials this machine cannot finish. Each costs the connect
        // budget on a black-holed route and costs nothing where the family is
        // not compiled in, so this is the slow half of the test either way.
        let dead: SocketAddr = "[2001:db8::1]:443".parse().expect("documentation address");
        for attempt in 1..=3 {
            let started = Instant::now();
            assert!(
                crate::proxy::dial_marked(dead).is_none(),
                "dial {attempt} must not land"
            );
            println!("dial {attempt} gave up after {:?}", started.elapsed());
        }
        assert!(
            super::super::egress::withhold_aaaa(),
            "three machine-level failures should have set the verdict"
        );

        let (rcode, records) = ask(dns::TYPE_AAAA);
        println!("AAAA after the dials: rcode={rcode} records={records}");
        assert_eq!(rcode, 0, "a withheld answer is a plain NOERROR, not an error");
        assert_eq!(records, 0, "no IPv6 record may reach a client it would strand");

        let (rcode, records) = ask(dns::TYPE_A);
        println!("A after the dials: rcode={rcode} records={records}");
        assert_eq!(rcode, 0);
        assert!(records > 0, "only the family that cannot be carried is held back");

        stop.store(true, Ordering::Relaxed);
        let _ = worker.join();
    }
}
