//! The filter strings the Windows redirect hands to WinDivert.
//!
//! Kept apart from the driver so the one thing that can be checked without a
//! Windows kernel — the shape of the grammar — is compiled and tested on every
//! platform. What the strings *mean* is `sys::windivert`'s business; how they
//! have to be written is this file's.

// The consumers are Windows-only, so on Linux every item here is "unused" and
// the tests below are the only reason it compiles at all. That is the trade:
// the grammar is checked where it can be checked.
#![allow(dead_code)]

/// The proxy's local port range, excluded from the filter: these are the
/// proxy's own connections to origins, and redirecting them would be a loop.
pub const OWN_PORTS: (u16, u16) = (40000, 40999);

/// The filter for traffic leaving the machine: the four protocols the redirect
/// has an opinion about, minus the proxy's own dialling and anything already
/// pointed inside.
pub(crate) fn outbound_filter() -> String {
    // No parentheses directly after `not`: WinDivert's parser answers
    // `not (tcp.DstPort == 80)` with ERROR_INVALID_PARAMETER, and an open that
    // fails with 87 is a redirect that silently never starts — the whole
    // Windows build filtered nothing for exactly this reason. `not x == y`
    // parses (negation binds tighter than `and`), and a range is written as its
    // complement instead of a group.
    format!(
        "outbound and not impostor \
         and (tcp.DstPort == 80 or tcp.DstPort == 443 \
              or udp.DstPort == 53 or udp.DstPort == 443) \
         and (tcp.SrcPort < {lo} or tcp.SrcPort > {hi}) \
         and (udp.SrcPort < {lo} or udp.SrcPort > {hi}) \
         and not ip.DstAddr == 127.0.0.1 \
         and not ipv6.DstAddr == ::1",
        lo = OWN_PORTS.0,
        hi = OWN_PORTS.1
    )
}

/// What the redirect has done, as one line, so a machine that is "protecting"
/// without moving a packet can be told apart from one that is.
///
/// Lives here, beside the filter grammar, because it is the same kind of thing:
/// the part of the Windows redirect that can be read and tested where there is
/// no Windows kernel.
pub fn report(seen: u64, redirected: u64, dropped: u64, flows: usize) -> String {
    if seen == 0 {
        // The diagnosis this line exists for: the handles opened — the log says
        // so — and the driver is handing nothing over. A least-privilege token,
        // another product's WinDivert handle, or a filter the driver accepted and
        // then never matched all end up here.
        return "[net] redirect: not intercepting — 0 packets seen; the driver is open but \
                nothing is reaching it"
            .to_string();
    }
    format!(
        "[net] redirect: {seen} packets, {redirected} redirected, {dropped} dropped, {flows} live flows"
    )
}

/// The filter for the answers: only the two local listeners can produce them.
pub(crate) fn inbound_filter(proxy_port: u16, dns_port: u16) -> String {
    format!(
        "inbound and not impostor and ((tcp.SrcPort == {proxy_port}) or (udp.SrcPort == {dns_port}))"
    )
}

#[cfg(test)]
mod report_tests {
    use super::report;

    /// A machine that is protected and a machine that is not look the same from
    /// the window; this line is the difference.
    #[test]
    fn a_redirect_that_saw_nothing_says_it_is_not_intercepting() {
        let line = report(0, 0, 0, 0);
        assert!(line.contains("not intercepting"), "{line}");
        assert!(!line.contains("0 redirected"), "{line}");
    }

    #[test]
    fn a_working_redirect_counts_what_it_did() {
        let line = report(1200, 800, 3, 7);
        assert!(line.contains("1200 packets"), "{line}");
        assert!(line.contains("800 redirected"), "{line}");
        assert!(line.contains("3 dropped"), "{line}");
        assert!(line.contains("7 live flows"), "{line}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// WinDivert's parser refuses a parenthesised expression after `not`.
    ///
    /// Measured on a Windows 11 machine against the shipped pair:
    /// `not (tcp.DstPort == 80)` returns `ERROR_INVALID_PARAMETER` (87) while
    /// `not tcp.DstPort == 80` opens a handle. The redirect treats an open
    /// failure as "no redirect" rather than as a fault, so one pair of
    /// parentheses is the whole difference between a filtering proxy and an app
    /// that sits in the tray claiming to protect a machine and moves no packet.
    ///
    /// Only the shape can be held here: the parser that rejected it is
    /// Windows-only code, so this test cannot discover a new corner of that
    /// grammar by itself.
    #[test]
    fn no_filter_puts_a_group_after_not() {
        let outbound = outbound_filter();
        assert!(
            !outbound.contains("not ("),
            "WinDivert rejects `not (…)` with error 87: {outbound}"
        );
        assert!(
            outbound.contains("not ip.DstAddr == 127.0.0.1"),
            "{outbound}"
        );
        assert!(outbound.contains("not ipv6.DstAddr == ::1"), "{outbound}");
        // The own-port range is still excluded, written as its complement: a
        // redirect that catches the proxy's own dialling is an endless loop.
        assert!(
            outbound.contains("tcp.SrcPort < 40000 or tcp.SrcPort > 40999"),
            "{outbound}"
        );
        assert!(
            outbound.contains("udp.SrcPort < 40000 or udp.SrcPort > 40999"),
            "{outbound}"
        );

        let inbound = inbound_filter(3129, 5300);
        assert!(!inbound.contains("not ("), "{inbound}");
        assert!(inbound.contains("tcp.SrcPort == 3129"), "{inbound}");
        assert!(inbound.contains("udp.SrcPort == 5300"), "{inbound}");
    }
}
