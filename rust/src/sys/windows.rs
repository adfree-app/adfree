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

/// No conntrack entry to read: the target is named by the request head, or by
/// the redirect's own bookkeeping (`winnat`) when the driver moved the flow.
pub fn original_destination(_socket: RawSock) -> Option<SocketAddr> {
    None
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
    use crate::sys::windivert_filter::OWN_PORTS;
    use std::sync::atomic::{AtomicU32, Ordering};

    /// Round-robin position, so two dials in the same millisecond do not fight
    /// over the same port.
    static NEXT: AtomicU32 = AtomicU32::new(0);

    let fd = socket as libc::RawFd;
    // An unbound socket still reports its family, which is what decides whether
    // the wildcard address to bind is 0.0.0.0 or ::.
    let mut probe: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
    let mut probe_len = std::mem::size_of::<libc::sockaddr_storage>() as libc::socklen_t;
    let family = if unsafe {
        libc::getsockname(
            fd,
            &mut probe as *mut libc::sockaddr_storage as *mut libc::sockaddr,
            &mut probe_len,
        )
    } == 0
    {
        i32::from(probe.ss_family)
    } else {
        libc::AF_INET
    };

    let (lo, hi) = OWN_PORTS;
    let span = u32::from(hi - lo) + 1;
    for _ in 0..span {
        let port = lo + (NEXT.fetch_add(1, Ordering::Relaxed) % span) as u16;
        if bind_wildcard(fd, family, port) {
            return;
        }
    }
    // Every port in the range is taken, which means the redirect will see this
    // dial as a client's. Failing here would fail the request; leaving it
    // unbound gives the driver a chance to hand it back, which at least does
    // not lose the connection outright.
    crate::logbuf::push("proxy: no free port in the own-traffic range; a dial may be redirected");
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
