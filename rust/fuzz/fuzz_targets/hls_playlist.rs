//! An HLS matcher is parsed out of a downloaded filter rule (`$hls=…`) and then
//! applied to every media segment a page requests. Both halves are fuzzed
//! here, because the value comes from a list and the body comes from the wire.

#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let Ok(text) = std::str::from_utf8(data) else {
        return;
    };

    let Some(matcher) = adfree_core::hls::parse(text) else {
        return;
    };
    // `HlsRule` is what a request's plan holds: the matcher plus the text it
    // came from, so the log can name the rule that acted.
    let rule = adfree_core::hls::HlsRule::new(text.to_string(), matcher);
    let rules = std::slice::from_ref(&rule);

    // The body of a playlist segment, and the same bytes as a segment URL:
    // both are user-controlled, and neither may panic while the rules decide.
    let _ = adfree_core::hls::apply_rules(rules, "https://video.example.test/live/1.ts", data);
    let _ = adfree_core::hls::apply_rules(rules, text, data);
});
