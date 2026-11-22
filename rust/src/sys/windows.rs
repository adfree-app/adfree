//! The Windows half of the seam.
//!
//! Unix moves traffic without asking the application, so the proxy lets the
//! kernel redirect it and then has to say "this one is mine" — with a firewall
//! mark on Linux, with a socket option on the BSDs. Windows interception is our
//! own driver's doing (`sys::windivert`), and it has no mark to read: the one
//! thing a packet carries that says where it came from is its source port.
//!
//! That is what [`mark_own_traffic`] arranges. The redirect's filter leaves the
//! range [`crate::sys::windivert_filter::OWN_PORTS`] alone, so the proxy's own
//! dialling has to *leave* from that range — bind before connect, not mark.

use std::net::SocketAddr;

pub use std::os::windows::raw::SOCKET as RawSock;

/// Where a redirected connection was headed.
///
/// Windows has no `SO_ORIGINAL_DST`, and the redirect leaves nothing in the
/// socket that names the origin — except the client's port, which it does not
/// rewrite. The outbound pass wrote that port's destination down
/// ([`crate::sys::redirect_targets`]), so the connection finds itself by the
/// peer it arrived from. An explicit-proxy request never came through the
/// redirect and has no entry, which is how it still falls back to the head.
pub fn original_destination(socket: RawSock) -> Option<SocketAddr> {
    let mut sa: crate::sys::libc::sockaddr_storage = unsafe { std::mem::zeroed() };
    let mut len =
        std::mem::size_of::<crate::sys::libc::sockaddr_storage>() as crate::sys::libc::socklen_t;
    let rc = unsafe {
        crate::sys::libc::getpeername(
            socket as crate::sys::libc::RawFd,
            &mut sa as *mut crate::sys::libc::sockaddr_storage as *mut crate::sys::libc::sockaddr,
            &mut len,
        )
    };
    if rc != 0 {
        return None;
    }
    let peer = crate::proxy::parse_sockaddr(&sa)?;
    crate::sys::redirect_targets::lookup(peer.port())
}

/// Waits for a socket to become writable, for a dial that is still connecting.
///
/// `WSAPoll` is not usable here, and the reason is not a translation mistake:
/// for a socket in a non-blocking connect it answers `SOCKET_ERROR` at once —
/// measured on the Windows box as `poll: WSAPoll nfds=1 asked=0x0030
/// timeout=9999 said rc=-1` for every dial. The dial then read "not writable"
/// one millisecond after the packet left, closed the socket and reported
/// `proxy: dial <ip>:443 failed` while the same dial from the same source port
/// answered `200` through `curl`. A machine with protection on loaded nothing.
///
/// `select` has been the way to wait on a connecting socket since before
/// `WSAPoll` existed: the socket appears in the write set when the attempt
/// finishes, successfully or not, and `SO_ERROR` then says which.
///
/// `None` means the wait itself failed, so the caller can fall back to `poll`.
pub fn wait_writable(socket: RawSock, timeout_ms: i32) -> Option<bool> {
    use windows_sys::Win32::Networking::WinSock as ws;
    let socket = socket as ws::SOCKET;
    let mut write: ws::FD_SET = unsafe { std::mem::zeroed() };
    write.fd_count = 1;
    write.fd_array[0] = socket;
    // The error set notices a refusal that arrives without readiness.
    let mut failed: ws::FD_SET = unsafe { std::mem::zeroed() };
    failed.fd_count = 1;
    failed.fd_array[0] = socket;
    let wait = ws::TIMEVAL {
        tv_sec: timeout_ms.max(0) / 1000,
        tv_usec: (timeout_ms.max(0) % 1000) * 1000,
    };
    let rc = unsafe { ws::select(0, std::ptr::null_mut(), &mut write, &mut failed, &wait) };
    if rc < 0 {
        return None;
    }
    if rc == 0 {
        return Some(false);
    }
    Some(write.fd_count > 0 || failed.fd_count > 0)
}

/// Puts the socket's source port inside the range the redirect filter skips.
///
/// Windows has no `SO_MARK`, so a socket that dials an origin from an ordinary
/// ephemeral port (Windows hands out 49152–65535) looks exactly like a browser
/// connection: the redirect catches it, hands it back to the proxy, and the
/// proxy dials again. The measured shape of that mistake is
/// `[core] proxy: dial <ip>:443 failed` for every request and a proxy that
/// accepts connections and closes them.
///
/// Binding to a port in the excluded range is the whole mechanism, and it has
/// to happen before `connect`. Ports are tried in rotation because one port is
/// one flow, and a busy proxy dials many origins at once.
pub fn mark_own_traffic(socket: RawSock, _mark: u32) {
    use crate::sys::libc;
    use crate::sys::windivert_filter::{own_port_state, OwnPort, OWN_PORTS};
    use std::sync::atomic::{AtomicU32, Ordering};

    /// Round-robin position, so two dials in the same millisecond do not fight
    /// over the same port.
    static NEXT: AtomicU32 = AtomicU32::new(0);
    static LOGGED: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
    static OUTSIDE: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);
    static BOUND: std::sync::atomic::AtomicBool = std::sync::atomic::AtomicBool::new(false);

    let fd = socket as libc::RawFd;
    // `getsockname` answers two things at once: the family (which decides
    // whether the wildcard to bind is 0.0.0.0 or ::) and whether the socket
    // already has a port. A socket can only be bound once, so the second answer
    // is the one that decides whether binding is even possible.
    let mut probe: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
    let mut probe_len = std::mem::size_of::<libc::sockaddr_storage>() as libc::socklen_t;
    let probed = unsafe {
        libc::getsockname(
            fd,
            &mut probe as *mut libc::sockaddr_storage as *mut libc::sockaddr,
            &mut probe_len,
        )
    } == 0;
    let family = if probed {
        i32::from(probe.ss_family)
    } else {
        libc::AF_INET
    };
    let entry_port = if probed { local_port(&probe) } else { 0 };

    match own_port_state(entry_port) {
        // The promise is already kept: the DNS half binds its forwarding socket
        // itself, and asking to bind it again failed on all thousand ports and
        // reported `no port in the own-traffic range could be bound (last error
        // 10022)` — an error that named the range when the socket was the
        // problem. Nothing to do, and nothing to say.
        OwnPort::AlreadyOurs => return,
        OwnPort::BoundElsewhere(port) => {
            if !OUTSIDE.swap(true, Ordering::Relaxed) {
                crate::logbuf::push_error(&format!(
                    "proxy: a socket already bound to port {port} is outside the                      own-traffic range ({}-{}), and a bound socket cannot be moved; \
                     a dial on it may be redirected",
                    OWN_PORTS.0, OWN_PORTS.1
                ));
            }
            return;
        }
        OwnPort::Unbound => {}
    }

    let (lo, hi) = OWN_PORTS;
    let span = u32::from(hi - lo) + 1;
    let mut last = 0;
    for _ in 0..span {
        let port = lo + (NEXT.fetch_add(1, Ordering::Relaxed) % span) as u16;
        if bind_wildcard(fd, family, port) {
            // Once per process, at the first success: the port is the whole
            // promise the redirect keeps about this dial, and a dial that is not
            // bound here is a dial the rules hand straight back to the proxy.
            if !BOUND.swap(true, Ordering::Relaxed) {
                crate::logbuf::push(&format!(
                    "proxy: own traffic bound to port {port} (family {family})"
                ));
            }
            return;
        }
        last = std::io::Error::last_os_error().raw_os_error().unwrap_or(-1);
        // `WSAEINVAL` and `WSAEAFNOSUPPORT` are properties of the socket, not of
        // the port: no other port in the range will answer differently, so the
        // other 999 attempts are the same expensive answer.
        if last == 10022 || last == 10047 {
            break;
        }
    }
    if !LOGGED.swap(true, Ordering::Relaxed) {
        // Once per process: a dial that cannot be marked is a dial the redirect
        // may catch and hand back, so the code matters even though nothing here
        // can fix it.
        crate::logbuf::push_error(&format!(
            "proxy: no port in the own-traffic range could be bound \
             (family {family}, last error {last} {}); a dial may be redirected",
            meaning(last)
        ));
    }
    // Every port in the range is taken, which means the redirect will see this
    // dial as a client's. Failing here would fail the request; leaving it
    // unbound gives the driver a chance to hand it back, which at least does
    // not lose the connection outright.
    crate::logbuf::push("proxy: no free port in the own-traffic range; a dial may be redirected");
}

/// The port `getsockname` just filled in, or 0 when the socket has none.
fn local_port(probe: &crate::sys::libc::sockaddr_storage) -> u16 {
    use crate::sys::libc;
    let family = i32::from(probe.ss_family);
    unsafe {
        if family == libc::AF_INET6 {
            let sin6 = &*(probe as *const libc::sockaddr_storage as *const libc::sockaddr_in6);
            u16::from_be(sin6.sin6_port)
        } else {
            let sin = &*(probe as *const libc::sockaddr_storage as *const libc::sockaddr_in);
            u16::from_be(sin.sin_port)
        }
    }
}

/// The Winsock error in words, so one line of the log names the fault.
fn meaning(code: i32) -> &'static str {
    match code {
        10013 => "(access forbidden: the port range is reserved by another component)",
        10022 => "(the socket cannot be bound: it already has a local address)",
        10047 => "(the socket's address family is not the one that was bound)",
        10048 => "(every port tried in the range is in use)",
        10049 => "(the address is not available on this machine)",
        _ => "(unrecognised)",
    }
}

/// Binds `fd` to the wildcard address of `family` with `port`. Returns whether
/// the kernel took it.
fn bind_wildcard(fd: crate::sys::libc::RawFd, family: i32, port: u16) -> bool {
    use crate::sys::libc;
    let addr = std::net::SocketAddr::new(
        if family == libc::AF_INET6 {
            std::net::IpAddr::V6(std::net::Ipv6Addr::UNSPECIFIED)
        } else {
            std::net::IpAddr::V4(std::net::Ipv4Addr::UNSPECIFIED)
        },
        port,
    );
    let mut sa: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
    let len = match addr {
        std::net::SocketAddr::V4(v4) => {
            let sin =
                unsafe { &mut *(&mut sa as *mut libc::sockaddr_storage as *mut libc::sockaddr_in) };
            sin.sin_family = libc::AF_INET as libc::sa_family_t;
            sin.sin_port = v4.port().to_be();
            sin.sin_addr.s_addr = 0;
            std::mem::size_of::<libc::sockaddr_in>() as libc::socklen_t
        }
        std::net::SocketAddr::V6(v6) => {
            let sin6 = unsafe {
                &mut *(&mut sa as *mut libc::sockaddr_storage as *mut libc::sockaddr_in6)
            };
            sin6.sin6_family = libc::AF_INET6 as libc::sa_family_t;
            sin6.sin6_port = v6.port().to_be();
            sin6.sin6_flowinfo = 0;
            sin6.sin6_addr.s6_addr = [0; 16];
            sin6.sin6_scope_id = 0;
            std::mem::size_of::<libc::sockaddr_in6>() as libc::socklen_t
        }
    };
    unsafe {
        libc::bind(
            fd,
            &sa as *const libc::sockaddr_storage as *const libc::sockaddr,
            len,
        ) == 0
    }
}
