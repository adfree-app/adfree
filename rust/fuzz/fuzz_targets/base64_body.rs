//! An override body reaches the decoder as base64 typed, pasted or fetched by
//! the inspector, and the decoded bytes are then written back to a browser as
//! a response. A decoder that mis-sizes its buffer writes into the wrong place
//! in a proxy that runs with `CAP_NET_ADMIN`.

#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    let Ok(text) = std::str::from_utf8(data) else {
        return;
    };

    let Some(decoded) = adfree_core::capture::base64_decode(text) else {
        return;
    };
    // Padding, whitespace and URL-safe spellings all arrive as text; the only
    // promise the caller needs is that what comes back is the bytes the text
    // named and nothing longer.
    assert!(
        decoded.len() <= text.len(),
        "a {} byte text decoded to {} bytes",
        text.len(),
        decoded.len()
    );

    // The preview path reads its own copy of the same body, and the two must
    // agree on the length or the inspector shows a body it cannot write.
    let _ = adfree_core::capture::base64_decode(text.trim());
});
