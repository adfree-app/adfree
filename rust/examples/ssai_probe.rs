//! What the URL layer does to a server-side-ad-insertion manifest.
//!
//! `cargo run --release --example ssai_probe` prints the verdict a player's
//! three request types get for the same manifest URL, plus the host answer,
//! which is how the `$xhr`-only block that leaves the video intact is checked.

fn main() {
    let core = adfree_core::engine::core();
    let fe = core.filter_engine();
    let url = "https://pubads.g.doubleclick.net/ssai/event/1Y_SLgNcSo-czjgARPOYGQ/master.m3u8";
    for (kind, ty) in [("xhr", "xmlhttprequest"), ("media", "media"), ("script", "script")] {
        let v = fe.check_url(url, "https://www.click2houston.com/", ty, "GET", &[]);
        println!("{kind:6} blocked={} redirect={} rule={:?} list={:?}", v.blocked, v.redirect.is_some(), v.rule_text, v.list_name);
    }
    let h = fe.check_host("pubads.g.doubleclick.net");
    println!("host   blocked={} rule={:?} list={:?} redirect={}", h.blocked, h.rule_text, h.list_name, h.redirect);
}
