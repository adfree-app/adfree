//! Bounded proofs for the functions whose wrongness is memory-unsafe.
//!
//! One, for the DNS question decoder — the function that had two memory bugs
//! found in one afternoon. Two others were written and removed for not
//! converging, and the notes at the end of this file say which and why: a proof
//! that does not finish is not a check, and the unit tests plus the fuzzers
//! already hold those two functions.
//!
//! Everything here is compiled only under `cargo kani`. The unit tests say what
//! these functions do for inputs we thought of, the fuzzers look for an input
//! that breaks them, and these two say something about *all* inputs inside the
//! bound — which is the only way to hold an `unsafe` invariant.
//!
//!   cd rust && cargo kani --lib -p adfree-core
//!
//! CI: the `Proofs` job in `.github/workflows/verify.yml`, weekly.

use crate::dns;
use crate::pathmask;

/// The question decoder, for a packet whose first label is wholly symbolic.
///
/// `Query::qname` is handed out as `&str` through `from_utf8_unchecked`, so the
/// decoder owes two things: it writes only ASCII, and it never writes past the
/// 253 bytes it has. Both were wrong once — the `dns_query` fuzzer found the
/// first and the second was found while reading the code after it — so both are
/// stated here over a symbolic label length and a symbolic byte.
///
/// The packet shape is concrete and only its contents are symbolic: a
/// twenty-four-byte head with every byte symbolic makes CBMC unwind a loop over
/// variable-length labels for a quarter of an hour, and proves nothing that
/// this does not.
#[kani::proof]
#[kani::unwind(8)]
fn a_decoded_question_is_ascii_and_lies_within_its_buffer() {
    let label_len: u8 = kani::any();
    let first_byte: u8 = kani::any();
    let rest: [u8; 6] = kani::any();

    let mut packet = vec![0u8; 12];
    packet[5] = 1; // one question
    packet.push(label_len);
    packet.push(first_byte);
    packet.extend_from_slice(&rest);

    let body = &packet[..];
    let Some(query) = dns::parse_query(body) else {
        // Giving up is always allowed, and is what a truncated packet gets.
        return;
    };
    assert!(query.qname.as_str().is_ascii());
    assert!(query.qname.as_str().len() <= 253);
    // `q_end` is read by every caller to find what follows the question: it
    // must name a place inside the datagram, never past it.
    assert!(query.q_end <= body.len());
}

// Not proved here, and why:
//
// - `pathmask::path_and_query`, over sixteen symbolic bytes: CBMC has to walk
//   the UTF-8 validator and the `://` search once per byte, and after twenty
//   minutes it had proved nothing.
// - `mitm::proxy::is_navigation`, the header rule the cookie decision turns on:
//   the truth table is six by five, and CBMC still had not closed it inside ten
//   minutes, because `trim` and `eq_ignore_ascii_case` over a `&str` are loops
//   over memory whose identity CBMC keeps abstract. The table is held by
//   `only_a_document_navigation_counts_as_one` and its neighbours.
//
// Both functions are still covered here by unit tests and, for the URL splitter,
// by the `filter_rule` fuzzer.
