//! The browser extension talks to the app over a length-prefixed frame. Any
//! page-side script can reach that endpoint, and a filled mailbox is a caller
//! that is not the extension at all.

#![no_main]

use libfuzzer_sys::fuzz_target;

fuzz_target!(|data: &[u8]| {
    // The reader takes anything that is `Read`; a slice is what the fuzzer
    // hands over, and a truncated frame is the shape that matters — the
    // length prefix promises more than the buffer holds.
    let mut input = data;
    if let Ok(Some(message)) = adfree_core::assistant::read_frame(&mut input) {
        // Whatever came back is handed to the JSON reader next; a frame the
        // extension never sent must not be able to make that one panic.
        let _ = serde_json::from_slice::<serde_json::Value>(&message);
        let _ = adfree_core::assistant::write_frame(&mut Vec::new(), &message);
    }

    // The frame is also read from the other end, in one call, by the tests and
    // by the host: the same bytes through the writer must not panic either.
    let _ = adfree_core::assistant::write_frame(&mut Vec::new(), data);
});
