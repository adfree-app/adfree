//! What the filter engine costs, on its own.
//!
//! The desktop app loads the engine inside the JVM, so its RSS cannot say how
//! much of the process is filtering and how much is Skiko/Mesa/metaspace. This
//! builds the same lists in a bare process and prints the resident cost of the
//! engine and of nothing else:
//!
//! ```sh
//! cargo run --release --example engine_mem
//! ```
//!
//! Linux only: the resident size comes from `/proc/self/status` and the pages
//! are handed back with `malloc_trim`, which is glibc's. The Windows port check
//! compiles every example for its target and neither exists there, so the
//! example stands down to a note rather than breaking that check.

#[cfg(target_os = "linux")]
use adfree_core::filter::{FilterEngine, FilterListInfo, ListFormat};
#[cfg(target_os = "linux")]
use std::path::PathBuf;

#[cfg(target_os = "linux")]
fn rss_kb() -> u64 {
    std::fs::read_to_string("/proc/self/status")
        .ok()
        .and_then(|s| {
            s.lines()
                .find(|l| l.starts_with("VmRSS:"))
                .and_then(|l| l.split_whitespace().nth(1).map(|v| v.parse().ok()))
                .flatten()
        })
        .unwrap_or(0)
}

#[cfg(target_os = "linux")]
fn main() {
    let dir = std::env::var("ADFREE_FILTER_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|_| {
            PathBuf::from(std::env::var("HOME").unwrap_or_default())
                .join(".local/share/adfree/filters")
        });

    let mut lists = Vec::new();
    let mut id = 1u16;
    let mut entries: Vec<PathBuf> = std::fs::read_dir(&dir)
        .expect("filter dir")
        .filter_map(|e| e.ok().map(|e| e.path()))
        .filter(|p| p.extension().is_some_and(|e| e == "txt"))
        .collect();
    entries.sort();
    for path in entries {
        let name = path
            .file_stem()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_default();
        // `ADFREE_SKIP=oisd,ag-dns` answers "what would turning it off save?".
        let skip: Vec<String> = std::env::var("ADFREE_SKIP")
            .unwrap_or_default()
            .split(',')
            .filter(|s| !s.is_empty())
            .map(|s| s.to_string())
            .collect();
        if skip.iter().any(|s| name.contains(s.as_str())) {
            continue;
        }
        // The two DNS lists as the catalogue enters them: AdGuard's filter is
        // adblock syntax, OISD's is dnsmasq behind a hosts row.
        let format = if name.contains("oisd") {
            ListFormat::Hosts
        } else {
            ListFormat::Standard
        };
        lists.push(FilterListInfo {
            id,
            name: name.clone(),
            url: String::new(),
            enabled: true,
            trusted: true,
            local_path: Some(path.clone()),
            format,
            category: adfree_core::filter::CAT_AD,
            rule_count: 0,
            last_error: None,
            dns_only: false,
        });
        id += 1;
        let size = std::fs::metadata(&path).map(|m| m.len()).unwrap_or(0);
        println!("list {name}: {:.1} MB", size as f64 / 1048576.0);
    }

    // A build allocates the parsed text of every list at once; what matters is
    // what the engine keeps, so hand the freed pages back before measuring.
    unsafe { libc::malloc_trim(0) };
    let before = rss_kb();
    let engine = FilterEngine::new();
    engine.rebuild(&lists, &[]);
    unsafe { libc::malloc_trim(0) };
    std::thread::sleep(std::time::Duration::from_millis(200));
    let after = rss_kb();

    println!(
        "engine text: {:.1} MB",
        engine.memory_bytes() as f64 / 1048576.0
    );
    println!(
        "rss: before {:.1} MB, after {:.1} MB, engine cost {:.1} MB",
        before as f64 / 1024.0,
        after as f64 / 1024.0,
        (after.saturating_sub(before)) as f64 / 1024.0
    );
}

#[cfg(not(target_os = "linux"))]
fn main() {
    eprintln!("engine_mem: the resident size of a list build is a Linux question");
}
