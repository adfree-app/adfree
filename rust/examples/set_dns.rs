//! Picks the resolver the engine forwards to, without the UI.
//!
//! ```sh
//! cargo run --release --example set_dns -- quad9
//! ```
//!
//! Stop the app first: it keeps the state in memory and would write its own
//! copy back over this one.

use adfree_core::state::StateStore;
use adfree_core::store::Store;
use std::sync::Arc;

fn main() {
    let id = std::env::args().nth(1).unwrap_or_else(|| "quad9".into());
    let root = std::env::var("ADFREE_DATA_DIR")
        .map(std::path::PathBuf::from)
        .unwrap_or_else(|_| {
            let base = std::env::var("XDG_DATA_HOME")
                .map(std::path::PathBuf::from)
                .unwrap_or_else(|_| {
                    std::path::PathBuf::from(std::env::var("HOME").expect("HOME"))
                        .join(".local/share")
                });
            base.join("adfree")
        });
    let store = Store::open(&root).expect("open the data store");
    let state = StateStore::open(Arc::new(store));
    let mut protection = state.protection();
    if state.snapshot().dns.provider(&id).is_none() {
        let known: Vec<String> = state
            .snapshot()
            .dns
            .providers
            .iter()
            .map(|p| p.id.clone())
            .collect();
        eprintln!("no provider {id}; known: {}", known.join(", "));
        std::process::exit(2);
    }
    protection.selected_dns_provider_id = id.clone();
    if !state.save_protection(protection) {
        eprintln!("the state store refused the write");
        std::process::exit(1);
    }
    println!("selected resolver: {id}");
}
