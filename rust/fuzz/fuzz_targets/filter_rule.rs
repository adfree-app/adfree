//! Filter rules arrive from a downloaded list, which is to say from the
//! network, and the engine parses thousands of them per list.
//!
//! The parsers here are the ones this app wrote on top of `adblock`: the `[$…]`
//! bracket a rule may open with, the `domain/path` spelling the engine has to
//! either honor or drop, and the cosmetic-line reader. A panic means a whole
//! list fails to load, because one bad line is enough.

#![no_main]

use adfree_core::htmlfilter::Parsed;
use adfree_core::pathmask::BracketParse;
use adfree_core::pathmask::TargetsParse;
use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let Ok(line) = std::str::from_utf8(data) else {
        return;
    };

    // `NotBracket` and `NotTargets` are ordinary answers, not failures: most
    // lines are neither.
    if let BracketParse::Parsed(bracket) = adfree_core::pathmask::parse_bracket(line) {
        let _ = bracket.domain.as_deref();
        let _ = bracket.rest.len();
        let _ = bracket.location();
    }

    if let TargetsParse::Targets(targets) = adfree_core::pathmask::parse_targets(line) {
        for target in &targets {
            let _ = target.domain.as_deref();
            let _ = target.path.is_some();
            let _ = target.authority.is_some();
        }
    }

    // The same input through the other readers the engine has: the URL split
    // that decides which part of a target a mask sees, the host rewrite, and
    // the cosmetic-line reader with its procedural selectors.
    let _ = adfree_core::pathmask::path_and_query(line);
    let _ = adfree_core::safesearch::safe_host(line);
    match adfree_core::htmlfilter::parse_line(line) {
        Parsed::Rule(_) | Parsed::Exception(_) => {}
        Parsed::Many {
            blocking,
            exceptions,
        } => {
            let _ = blocking.len() + exceptions.len();
        }
        _ => {}
    }
});
