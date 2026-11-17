//! What a host-only list costs, measured both ways.
//!
//! Runs the AdGuard DNS filter through the two paths the engine has: the plain
//! domain set (`dns_only`, what ships) and the full adblock compile (the same
//! list as an ordinary filter, which is what the host-only engine used to do).
//! Prints wall time and resident bytes for each, so the difference is a number
//! rather than a claim.
//!
//!   cargo run --release --example dns_cost -- ~/.local/share/adfree/filters/dns_default_ag.txt

use std::time::Instant;

use adfree_core::filter::{FilterEngine, FilterListInfo, ListFormat};

fn rss_kb() -> u64 {
    std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|status| {
            status
                .lines()
                .find(|line| line.starts_with("VmRSS:"))
                .and_then(|line| line.split_whitespace().nth(1)?.parse().ok())
        })
        .unwrap_or(0)
}

fn run(path: &str, dns_only: bool) -> (u128, u64) {
    let before = rss_kb();
    let engine = FilterEngine::new();
    let list = FilterListInfo {
        id: 1,
        name: "AdGuard DNS filter".to_string(),
        url: String::new(),
        enabled: true,
        trusted: true,
        local_path: Some(path.into()),
        format: ListFormat::Standard,
        category: 1,
        rule_count: 0,
        last_error: None,
        dns_only,
    };
    let started = Instant::now();
    engine.rebuild(&[list], &[]);
    let build = started.elapsed().as_millis();
    // Touch the structure once so lazily mapped pages are resident for both.
    let probe = engine.check_host("namebrain.org");
    assert!(probe.blocked, "the shipped list blocks its own names");
    let after = rss_kb();

    println!(
        "{}: build {build} ms, resident +{} MB ({} -> {} MB), blocked={}",
        if dns_only { "domain set" } else { "full engine" },
        (after - before) / 1024,
        before / 1024,
        after / 1024,
        probe.blocked,
    );
    (build, after - before)
}

fn main() {
    // One mode per process: a second build in the same process would reuse the
    // arenas the first one freed and measure the allocator, not the path.
    if let Some(mode) = std::env::args().nth(2) {
        let path = std::env::args()
            .nth(1)
            .unwrap_or_else(|| "/home/shin/.local/share/adfree/filters/dns_default_ag.txt".into());
        run(&path, mode == "set");
        return;
    }
    let path = std::env::args()
        .nth(1)
        .unwrap_or_else(|| "/home/shin/.local/share/adfree/filters/dns_default_ag.txt".into());
    let (set_build, set_rss) = run(&path, true);
    let (engine_build, engine_rss) = run(&path, false);
    println!(
        "difference: build {} ms, resident {} MB",
        engine_build.saturating_sub(set_build),
        (engine_rss as i64 - set_rss as i64) / 1024,
    );
}
