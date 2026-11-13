//! What a real uBlock-Origin rule turns into, printed.
//!
//! ```sh
//! cargo run --release --example ubo_syntax_probe
//! ```
//!
//! The rules below are the shapes uBO's own lists use. What is not printed is
//! not supported: a rule whose operator the engine does not know is dropped,
//! not half-applied.

use adfree_core::filter::{FilterEngine, FilterListInfo, ListFormat};

fn main() {
    let dir = std::env::temp_dir().join("adfree-ubo-probe");
    std::fs::create_dir_all(&dir).unwrap();
    // Rules taken from what uBO lists actually ship today.
    let body = "\
! uBO syntax probe
example.com##div:has(> .ad-slot)
example.com##.sponsored:has-text(Sponsored)
example.com##div:has-text(Quảng cáo) > .box
example.com##div:min-text-length(120):has-text(ad)
example.com##div:matches-css(display: none)
example.com##div:matches-css-before(content: 'Ad')
example.com##div:matches-attr(src=/ads/)
example.com##div:upward(2)
example.com##div:xpath(//div[@id='ad'])
example.com##.banner:style(display: none !important; height: 0)
example.com##.cookie:remove-attr(data-x)
example.com##.promo:remove-class(sponsored)
example.com##.popup:remove()
example.com##div:has-text(Sponsored):upward(3):remove()
example.com##div:-abp-has(.ad)
example.com##div:not(:has(.keep))
example.com#%#//scriptlet('prevent-fetch', 'doubleclick.net')
example.com#%#//scriptlet('json-prune-fetch-response', 'ad')
example.com#%#//scriptlet('set-constant', 'adsEnabled', 'false')
example.com#%#//scriptlet('abort-on-property-read', 'detectAdBlock')
example.com#%#//scriptlet('watch-attr', 'src', '.ad') // uBO-only operator
example.com##div:nth-ancestor(3)
example.com#?#.text-center:-abp-has(small:-abp-contains(QUANG CAO))
example.com#?#div:has-text(Ad):-abp-has(> .slot)
example.com##div:has-attr(data-ad)
";
    let path = dir.join("ubo-probe.txt");
    std::fs::write(&path, body).unwrap();
    let engine = FilterEngine::new();
    engine.rebuild(
        &[FilterListInfo {
            id: 1,
            name: "ubo-probe.txt".into(),
            url: String::new(),
            enabled: true,
            trusted: true,
            local_path: Some(path),
            format: ListFormat::Standard,
            category: adfree_core::filter::CAT_AD,
            rule_count: 0,
            last_error: None,
            dns_only: false,
        }],
        &[],
    );

    let res = engine.cosmetic_resources_for_host("example.com");
    println!("hide_selector ({}):", res.hide_selectors.len());
    for s in &res.hide_selectors {
        println!("  {s}");
    }
    println!("style_rules ({}):", res.style_rules.len());
    for (sel, css) in &res.style_rules {
        println!("  {sel} -> {css}");
    }
    println!("procedural ({}):", res.procedural.len());
    for p in &res.procedural {
        println!("  {p}");
    }
    let fns: Vec<&str> = res
        .scriptlets
        .lines()
        .filter_map(|l| l.strip_prefix("function "))
        .filter_map(|l| l.split('(').next())
        .collect();
    println!("scriptlets ({}): {:?}", fns.len(), fns);
    println!("jsrules: {}", res.jsrules.len());
}
