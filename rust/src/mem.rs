//! Handing freed memory back to the operating system.
//!
//! glibc keeps a freed chunk in its arena and only gives the page back when it
//! happens to sit at the top of the heap. A process that loads a few hundred
//! thousand rules, swaps them for a new build, and then idles therefore holds
//! megabytes of memory it will never touch again — the resident size only
//! grows, and an app that has been through one list reload looks like it is
//! leaking when it is only hoarding.
//!
//! Measured on this machine (Linux, glibc, 40 minutes of browsing through the
//! proxy): 444 MB resident before the call, 401 MB right after it. The 43 MB
//! were free either way; the difference is who owns them.

/// Returns the pages the allocator is holding on to with nothing in them.
///
/// Returns whether anything was actually released. On a platform without
/// `malloc_trim` this is a no-op that answers `false`, so callers need no
/// `cfg`.
#[cfg(all(target_os = "linux", target_env = "gnu"))]
pub fn trim() -> bool {
    extern "C" {
        fn malloc_trim(pad: usize) -> i32;
    }
    // `pad` is the number of bytes to leave at the top of the heap; zero means
    // "release everything you can". The call takes the allocator lock, so it
    // is kept off the packet path — see the callers.
    unsafe { malloc_trim(0) == 1 }
}

#[cfg(not(all(target_os = "linux", target_env = "gnu")))]
pub fn trim() -> bool {
    false
}

/// How often a long-running process should hand memory back. Five minutes is
/// short enough to keep a browsing session from settling at its peak, long
/// enough that the allocator lock is not worth noticing.
pub const TRIM_INTERVAL_MS: u64 = 5 * 60 * 1_000;

/// Whether `last_ms` is old enough to trim again at `now_ms`. A clock that
/// went backwards (suspend, an adjusted wall clock) counts as due rather than
/// never: the cost of one extra call is nothing.
pub fn due(last_ms: u64, now_ms: u64) -> bool {
    now_ms < last_ms || now_ms - last_ms >= TRIM_INTERVAL_MS
}

#[cfg(all(test, target_os = "linux", target_env = "gnu"))]
mod tests {
    use super::*;

    fn rss_kb() -> u64 {
        std::fs::read_to_string("/proc/self/status")
            .ok()
            .and_then(|text| {
                text.lines()
                    .find(|line| line.starts_with("VmRSS:"))
                    .and_then(|line| line.split_whitespace().nth(1)?.parse().ok())
            })
            .unwrap_or(0)
    }

    /// The point of the call: memory that was freed is memory the process no
    /// longer holds. Sized so the answer cannot be a page or two of noise.
    ///
    /// The allocations live and die on this test's own thread: a fresh arena
    /// grows under them and nothing else is left in it, so once they are gone
    /// the whole arena is free and the trim has something to give back.
    #[test]
    fn a_freed_arena_goes_back_to_the_system() {
        // What is measured here is glibc's allocator. A sanitizer replaces it
        // and Miri emulates it, so there is no arena to trim and the numbers
        // say nothing; the sanitizer job sets this and the reading is skipped
        // rather than reported as a failure of the code under test.
        if std::env::var_os("ADFREE_SKIP_ALLOCATOR_TEST").is_some() {
            return;
        }
        let before = rss_kb();
        let mut blocks: Vec<Vec<u8>> = Vec::with_capacity(4096);
        for _ in 0..4096 {
            // Under the mmap threshold on purpose: one arena, not 4096
            // mappings that would be unmapped on free anyway.
            blocks.push(vec![7u8; 64 * 1024]);
        }
        // Belt and braces: the compiler is entitled to delete a malloc/free
        // pair it can prove unobservable, and an allocation that never
        // happened cannot be trimmed.
        std::hint::black_box(&blocks);
        let grown = rss_kb();
        assert!(
            grown > before + 100_000,
            "the test did not allocate enough to be worth measuring: {before} kB -> {grown} kB"
        );
        drop(blocks);
        assert!(trim(), "the trim released nothing");
        let after = rss_kb();
        assert!(
            after + 100_000 < grown,
            "trim kept the arena: {grown} kB -> {after} kB"
        );
    }

    #[test]
    fn a_trim_is_due_once_per_interval() {
        assert!(!due(0, TRIM_INTERVAL_MS - 1));
        assert!(due(0, TRIM_INTERVAL_MS));
        assert!(due(TRIM_INTERVAL_MS, 2 * TRIM_INTERVAL_MS));
        // A clock that stepped back is not a reason to wait a whole interval.
        assert!(due(10 * TRIM_INTERVAL_MS, 5));
    }

    #[test]
    fn trimming_an_idle_process_is_harmless() {
        // No allocation to release is the normal answer, not a failure.
        let _ = trim();
        let _ = trim();
    }
}
