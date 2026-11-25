//! Every DNS packet the machine answers starts here.
//!
//! The resolver is reachable from any program on the machine and from anyone on
//! the local network, so this input is hostile by definition. A panic on a
//! malformed question is a resolver that stops resolving — and, because the
//! parse runs on the forwarding path, a hang is every name timing out at once.

#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let Some(query) = adfree_core::dns::parse_query(data) else {
        return;
    };
    // Read every field the caller reads, so a field that is derived lazily is
    // derived here too.
    let _ = query.id;
    let _ = query.flags;
    let _ = query.qd_count;
    let _ = query.qtype;
    let _ = query.qclass;
    let _ = query.has_question;
    let _ = query.q_end;
    let _ = query.qname.as_str();
    let _ = query.qname.is_empty();

    // The other direction: whatever was parsed must be answerable, and building
    // a reply must not walk off the end of the question.
    if let Some(reply) = adfree_core::dns::build_query(query.qname.as_str(), query.qtype, query.id) {
        let _ = adfree_core::dns::parse_query(&reply);
    }
});
