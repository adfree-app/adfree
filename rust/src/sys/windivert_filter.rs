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

/// The proxy's local port range, excluded from the redirect's opinion: these
/// are the proxy's own connections to origins, and redirecting them would be a
/// loop. The driver filter no longer names it — see [`outbound_filter`] — but
/// the packet code still uses it, and so do the tests that prove the loop
/// cannot happen.
pub const OWN_PORTS: (u16, u16) = (40000, 40999);

/// The filter for traffic leaving the machine: the four cases the redirect has
/// an opinion about, and nothing else.
///
/// It is deliberately the shortest filter that works, because on this grammar
/// the longest one that *parses* is not the longest one that *matches*. Measured
/// on a Windows 11 machine against the shipped 2.2.2 pair, with `netdump` and
/// these very strings as the probe, on a browsing machine:
///
/// ```text
/// outbound and tcp.DstPort == 443                                   654 packets
/// outbound and tcp.DstPort == 443 and not impostor                    0
/// outbound and tcp.DstPort == 443 and !impostor                     243
/// outbound and tcp.DstPort == 443 and ip.DstAddr != 127.0.0.1         0
/// outbound and tcp.DstPort == 443 and not (tcp.DstPort == 3129)     parse error
/// outbound and tcp.DstPort == 443 and (udp.SrcPort < 40000 or …)      0
/// ```
///
/// Every `not`, and every `!=`, silently emptied the filter while
/// `WinDivertOpen` kept returning success — so the app sat in the tray saying it
/// protected the machine and moved no packet at all, which is exactly what the
/// extension saw when it reported an install that blocked nothing. The
/// exclusions the old filter spelled out are therefore made in code, where they
/// can be expressed exactly and tested without a kernel: [`crate::winnat`]
/// passes the proxy's own dialling, traffic already pointed inside, and
/// loopback.
pub(crate) fn outbound_filter(proxy_port: u16, dns_port: u16) -> String {
    // Measured on the same machine, with `netdump` as the probe: loopback
    // traffic is reported **outbound only** — `loopback and inbound` matched
    // nothing while `loopback and outbound` matched hundreds — so the answers
    // the local listeners write, which are loopback packets by definition, are
    // only ever visible here. They are the second branch; everything else is
    // the client traffic the redirect exists for.
    format!(
        "outbound and ((tcp.DstPort == 80 or tcp.DstPort == 443 \
         or udp.DstPort == 53 or udp.DstPort == 443) \
         or (loopback and (tcp.SrcPort == {proxy_port} or udp.SrcPort == {dns_port})))"
    )
}

/// The filter for the answers: only the two local listeners can produce them.
pub(crate) fn inbound_filter(proxy_port: u16, dns_port: u16) -> String {
    format!("inbound and ((tcp.SrcPort == {proxy_port}) or (udp.SrcPort == {dns_port}))")
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

    /// The grammar this build once shipped matched nothing while it parsed.
    ///
    /// `WinDivertOpen` answers 87 for a filter it will not parse, and success
    /// for one it will parse and never match — the second is the dangerous
    /// answer, because it looks like protection. The probe table above is the
    /// measurement; this test keeps the filter inside the shapes that were
    /// measured to match.
    #[test]
    fn the_filter_names_no_negation() {
        let outbound = outbound_filter(3129, 5300);
        assert!(!outbound.contains("not"), "{outbound}");
        assert!(!outbound.contains('!'), "{outbound}");
        assert!(outbound.starts_with("outbound and ("), "{outbound}");
        // The answers the local listeners write are loopback packets, and the
        // driver reports those as outbound: without this branch the client never
        // receives an answer at all.
        assert!(outbound.contains("loopback and (tcp.SrcPort == 3129"), "{outbound}");
        assert!(outbound.contains("udp.SrcPort == 5300"), "{outbound}");
        // The four cases the redirect has an opinion about.
        for want in [
            "tcp.DstPort == 80",
            "tcp.DstPort == 443",
            "udp.DstPort == 53",
            "udp.DstPort == 443",
        ] {
            assert!(outbound.contains(want), "{want} missing: {outbound}");
        }

        let inbound = inbound_filter(3129, 5300);
        assert!(!inbound.contains("not"), "{inbound}");
        assert!(inbound.contains("tcp.SrcPort == 3129"), "{inbound}");
        assert!(inbound.contains("udp.SrcPort == 5300"), "{inbound}");
    }
}
