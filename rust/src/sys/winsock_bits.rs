//! Winsock's `poll` numbering, translated to and from libc's.
//!
//! `WSAPoll` takes and returns *Winsock* bits, and they are not libc's bits
//! under another name — a serial port (0x001) and Winsock's `POLLERR` are the
//! same number as libc's `POLLIN`, and Winsock's `POLLWRNORM` (0x010) is the
//! number libc gives `POLLHUP`. Comparing a `WSAPOLLFD.revents` against a
//! libc-numbered constant therefore does not merely miss: it invents.
//!
//! It invented this on a real machine. `poll` asked for readable sockets, the
//! reply was folded back with libc's numbering, and every socket that was
//! *writable* came back carrying libc's `POLLHUP`. `drainable` — rightly, for
//! its own numbering — calls any non-zero answer a reason to read again, so a
//! writable socket read `EWOULDBLOCK`, waited, and was told again it was hung
//! up: three cores pinned on a four-core Windows box, with every real error and
//! every end-of-stream invisible underneath.
//!
//! The translation lives here, in a file compiled on every platform, so the
//! arithmetic has tests that run on the machine the developer is sitting at and
//! not only on the one that broke.

/// Winsock's own numbering, quoted from `winsock2.h`.
mod winsock {
    pub const RDNORM: i16 = 0x0100;
    pub const RDBAND: i16 = 0x0200;
    pub const WRNORM: i16 = 0x0010;
    pub const WRBAND: i16 = 0x0020;
    pub const PRI: i16 = 0x0400;
    pub const ERR: i16 = 0x0001;
    pub const HUP: i16 = 0x0002;
    pub const NVAL: i16 = 0x0004;
}

/// libc's numbering, which is what the forwarding code says everywhere else.
mod shim {
    pub const IN: i16 = 0x001;
    pub const PRI: i16 = 0x002;
    pub const OUT: i16 = 0x004;
    pub const ERR: i16 = 0x008;
    pub const HUP: i16 = 0x010;
    pub const NVAL: i16 = 0x020;
}

/// What `WSAPoll` should be asked for, given a libc-numbered `events` mask.
///
/// `POLLERR`, `POLLHUP` and `POLLNVAL` are not requestable: Winsock reports
/// them whether or not they were asked for, so asking is glue, not intent.
pub fn request(events: i16) -> i16 {
    let mut out = 0;
    if events & shim::IN != 0 {
        out |= winsock::RDNORM | winsock::RDBAND;
    }
    if events & shim::OUT != 0 {
        out |= winsock::WRNORM | winsock::WRBAND;
    }
    if events & shim::PRI != 0 {
        out |= winsock::PRI;
    }
    out
}

/// What a caller should be told, given a `WSAPOLLFD.revents` mask.
///
/// The returned mask is in libc's numbering, so `drainable` and every
/// `& POLLIN` test downstream keep reading what they were written to read.
pub fn revents(winsock: i16) -> i16 {
    let mut out = 0;
    if winsock & (winsock::RDNORM | winsock::RDBAND) != 0 {
        out |= shim::IN;
    }
    if winsock & (winsock::WRNORM | winsock::WRBAND) != 0 {
        out |= shim::OUT;
    }
    if winsock & winsock::ERR != 0 {
        out |= shim::ERR;
    }
    if winsock & winsock::HUP != 0 {
        out |= shim::HUP;
    }
    if winsock & winsock::NVAL != 0 {
        out |= shim::NVAL;
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The collision itself, pinned: if these two numbers ever stop being equal
    /// this test has nothing left to warn about, and until then it is the
    /// reason the fold must not use one numbering to read the other.
    #[test]
    fn libc_hang_up_is_windowss_write_readiness() {
        assert_eq!(shim::HUP, winsock::WRNORM);
        assert_ne!(shim::HUP, winsock::HUP);
    }

    /// The fault as it happened: a writable socket must not read as readable,
    /// hung up or failed.
    #[test]
    fn a_writable_socket_is_only_writable() {
        let told = revents(winsock::WRNORM);
        assert_eq!(told, shim::OUT);
        assert_eq!(told & (shim::IN | shim::HUP | shim::ERR), 0);
    }

    /// A socket with data, in either read band, is readable.
    #[test]
    fn data_in_either_band_is_readable() {
        assert_eq!(revents(winsock::RDNORM) & shim::IN, shim::IN);
        assert_eq!(revents(winsock::RDBAND) & shim::IN, shim::IN);
    }

    /// An error, an end-of-stream and a bad descriptor all survive the fold —
    /// they are the three answers a wait loop cannot afford to lose.
    #[test]
    fn the_three_answers_a_loop_needs_survive() {
        assert_eq!(revents(winsock::ERR), shim::ERR);
        assert_eq!(revents(winsock::HUP), shim::HUP);
        assert_eq!(revents(winsock::NVAL), shim::NVAL);
    }

    /// A socket that is both writable and readable says both, and nothing else.
    #[test]
    fn readiness_adds_up_without_inventing() {
        let told = revents(winsock::RDNORM | winsock::WRNORM);
        assert_eq!(told, shim::IN | shim::OUT);
    }

    /// Asking is translated in the same two bands, and the bits that cannot be
    /// asked for are not sent.
    #[test]
    fn what_is_asked_for_is_what_is_understood() {
        assert_eq!(request(shim::IN) & winsock::RDNORM, winsock::RDNORM);
        assert_eq!(request(shim::OUT) & winsock::WRNORM, winsock::WRNORM);
        assert_eq!(request(shim::ERR | shim::HUP | shim::NVAL), 0);
        assert_eq!(request(0), 0);
    }
}
