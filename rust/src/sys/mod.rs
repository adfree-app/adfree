//! What the proxy needs from the operating system, in one place.
//!
//! Two things differ between Linux and Windows and change nothing above them:
//!
//! * where a redirected connection was headed **before** the rules moved it —
//!   Linux answers that with `SO_ORIGINAL_DST` from conntrack, Windows has no
//!   such question because its interception names the target itself;
//! * how a socket the proxy dials out is kept out of the redirect rules —
//!   Linux uses a firewall mark, Windows needs nothing.
//!
//! Everything else the core does (rule matching, cosmetic injection, TLS
//! rewriting, the log store) is already portable, so this module is the seam
//! where a second platform starts rather than a port of its own.

#[cfg(unix)]
mod unix;
#[cfg(windows)]
mod windows;

/// The Windows redirect: WinDivert loaded at run time, driven by `winnat`.
#[cfg(windows)]
pub mod windivert;
/// The WinDivert filter strings: Windows-only consumers, cross-platform tests.
pub mod windivert_filter;

/// Who owns a socket on Windows: the IP helper owner tables, and the process
/// API for the name.
#[cfg(windows)]
pub mod winproc;

/// Winsock's `poll` numbering, folded to and from libc's.
///
/// Compiled on every platform on purpose: the arithmetic is the part that broke
/// on Windows, and a test that only runs on Windows is a test nobody runs.
pub mod winsock_bits;

/// The `libc` the proxy sees: the real one on Unix, and a Winsock-backed
/// namesake on Windows (`sys::winlibc`). Modules say `use crate::sys::libc;`
/// once instead of `extern crate libc`, so both platforms compile the same
/// forwarding code.
#[cfg(unix)]
pub use ::libc;
#[cfg(windows)]
#[doc(hidden)]
pub mod winlibc;
#[cfg(windows)]
pub use winlibc as libc;

#[cfg(unix)]
pub use unix::*;
#[cfg(windows)]
pub use windows::*;

/// Descriptor vocabulary: file descriptors on Unix, socket handles on Windows.
///
/// The proxy's own code says `RawFd` and `as_raw_fd()` everywhere; on Windows
/// those names are backed by the socket-oriented traits, which have the same
/// shape under different names.
#[cfg(unix)]
pub mod fd {
    pub use std::os::unix::io::{AsRawFd, FromRawFd, IntoRawFd, RawFd};

    /// "No descriptor": the sentinel the proxy stores where Unix would say `-1`.
    pub const NO_FD: RawFd = -1;
    /// The descriptor as the `i32` the app-side hooks speak (`VpnService.protect`).
    /// On Unix this is the type already; on Windows a `SOCKET` is pointer-sized,
    /// which is why the cast lives here instead of at each call site.
    pub fn as_i32(fd: RawFd) -> i32 {
        fd
    }
    /// Whether a stored descriptor names a live socket.
    pub fn fd_ok(fd: RawFd) -> bool {
        fd >= 0
    }
    /// A descriptor in an atomic cell (the engine's TUN handle).
    pub type AtomicFd = std::sync::atomic::AtomicI32;

    /// The wait set's kernel handle: here, an `epoll` descriptor.
    pub type Epoll = RawFd;
    pub const NO_EPOLL: Epoll = -1;
    pub fn epoll_ok(ep: Epoll) -> bool {
        ep >= 0
    }
    pub fn epoll_create1(flags: i32) -> Epoll {
        unsafe { ::libc::epoll_create1(flags) }
    }
    pub fn epoll_ctl(ep: Epoll, op: i32, fd: RawFd, ev: &mut ::libc::epoll_event) -> i32 {
        unsafe { ::libc::epoll_ctl(ep, op, fd, ev) }
    }
    pub fn epoll_wait(ep: Epoll, evs: &mut [::libc::epoll_event], timeout: i32) -> i32 {
        unsafe { ::libc::epoll_wait(ep, evs.as_mut_ptr(), evs.len() as i32, timeout) }
    }
    pub fn epoll_close(ep: Epoll) {
        unsafe {
            ::libc::close(ep);
        }
    }
}

#[cfg(windows)]
pub mod fd {
    use std::net::{TcpListener, TcpStream, UdpSocket};
    use std::os::windows::io::{AsRawSocket, FromRawSocket, IntoRawSocket, RawSocket};

    /// Winsock's `SOCKET`: pointer-sized, and `INVALID_SOCKET` is all bits set.
    pub type RawFd = RawSocket;

    /// "No descriptor": `INVALID_SOCKET`, which is *not* `-1` on Windows.
    pub const NO_FD: RawFd = RawFd::MAX;
    /// The descriptor as the `i32` the app-side hooks speak. A `SOCKET` is
    /// pointer-sized and its values fit in 32 bits on every Windows build the
    /// app targets; the truncation is the API's, not ours.
    pub fn as_i32(fd: RawFd) -> i32 {
        fd as i32
    }
    /// Whether a stored descriptor names a live socket.
    pub fn fd_ok(fd: RawFd) -> bool {
        fd != NO_FD
    }
    /// A descriptor in an atomic cell (the engine's TUN handle).
    pub type AtomicFd = std::sync::atomic::AtomicU64;

    /// The wait set's kernel handle. Windows has no `epoll`, so this is a wepoll
    /// instance — an IOCP port dressed in epoll's clothes — and the four calls
    /// below are the same four the Unix branch makes. One wait set, one set of
    /// semantics, no second implementation to keep in step.
    pub type Epoll = isize;
    pub const NO_EPOLL: Epoll = 0;
    pub fn epoll_ok(ep: Epoll) -> bool {
        ep != NO_EPOLL
    }
    pub fn epoll_create1(flags: i32) -> Epoll {
        unsafe { crate::sys::winlibc::wepoll_create1(flags) }
    }
    pub fn epoll_ctl(
        ep: Epoll,
        op: i32,
        fd: RawFd,
        ev: &mut crate::sys::winlibc::epoll_event,
    ) -> i32 {
        unsafe { crate::sys::winlibc::wepoll_ctl(ep, op, fd, ev) }
    }
    pub fn epoll_wait(
        ep: Epoll,
        evs: &mut [crate::sys::winlibc::epoll_event],
        timeout: i32,
    ) -> i32 {
        unsafe { crate::sys::winlibc::wepoll_wait(ep, evs, timeout) }
    }
    pub fn epoll_close(ep: Epoll) {
        unsafe {
            crate::sys::winlibc::wepoll_close(ep);
        }
    }

    pub trait AsRawFd {
        fn as_raw_fd(&self) -> RawFd;
    }

    pub trait IntoRawFd {
        fn into_raw_fd(self) -> RawFd;
    }

    pub trait FromRawFd {
        /// # Safety
        /// The handle must be a live socket nobody else closes.
        unsafe fn from_raw_fd(fd: RawFd) -> Self;
    }

    macro_rules! impl_traits {
        ($($t:ty),*) => {$(
            impl AsRawFd for $t {
                fn as_raw_fd(&self) -> RawFd {
                    self.as_raw_socket()
                }
            }
            impl IntoRawFd for $t {
                fn into_raw_fd(self) -> RawFd {
                    self.into_raw_socket()
                }
            }
            impl FromRawFd for $t {
                unsafe fn from_raw_fd(fd: RawFd) -> Self {
                    unsafe { <$t>::from_raw_socket(fd) }
                }
            }
        )*};
    }

    impl_traits!(TcpStream, TcpListener, UdpSocket);
}

/// Positional file reads: `FileExt::read_at` on Unix, `seek_read` on Windows.
pub fn read_at(file: &std::fs::File, buf: &mut [u8], offset: u64) -> std::io::Result<usize> {
    #[cfg(unix)]
    {
        use std::os::unix::fs::FileExt as _;
        file.read_at(buf, offset)
    }
    #[cfg(windows)]
    {
        use std::os::windows::fs::FileExt as _;
        file.seek_read(buf, offset)
    }
}

/// Whether a packet to a global IPv6 address could leave this machine at all.
///
/// The question is asked of the routing table, not of the network: connecting a
/// `UDP` socket sends nothing, and a machine with no IPv6 address or no route
/// to one answers `ENETUNREACH` straight away. That is the whole difference
/// this app needs, and it is the difference between working and not working on
/// a machine with IPv4 only — see [`crate::proxy::dns`] for what the resolver
/// does with the answer.
///
/// It is cached for ten seconds rather than asked per query, because the
/// answer only changes when a link comes up or goes down, and a resolver under
/// load should not pay two syscalls per name.
pub fn can_dial_ipv6() -> bool {
    use std::sync::atomic::{AtomicU64, Ordering};
    static VERDICT: AtomicU64 = AtomicU64::new(0);

    let now_ms = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0);
    // Zero means "never asked"; otherwise the low bit is the answer and the
    // rest is when it was taken.
    let seen = VERDICT.load(Ordering::Relaxed);
    if seen != 0 {
        let taken_ms = seen >> 1;
        if now_ms.saturating_sub(taken_ms) < 10_000 {
            return seen & 1 == 1;
        }
    }

    let answer = probe_ipv6_route();
    VERDICT.store((now_ms << 1) | u64::from(answer), Ordering::Relaxed);
    answer
}

fn probe_ipv6_route() -> bool {
    // A Cloudflare resolver address: connecting a datagram socket to it writes
    // no packet and resolves nothing. All that is learned is whether the kernel
    // has somewhere to send it.
    let (Ok(any_v6), Ok(destination)) = (
        "[::]:0".parse::<std::net::SocketAddr>(),
        "[2606:4700:4700::1111]:53".parse::<std::net::SocketAddr>(),
    ) else {
        return false;
    };
    let Ok(socket) = std::net::UdpSocket::bind(any_v6) else {
        // No IPv6 stack on this machine at all.
        return false;
    };
    socket.connect(destination).is_ok()
}
