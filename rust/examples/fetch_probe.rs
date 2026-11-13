//! Fetches one URL through the download path, so the resolver and the timeout
//! can be checked without starting the app.
fn main() {
    let url = std::env::args().nth(1).unwrap_or_else(|| "https://example.com/".into());
    let started = std::time::Instant::now();
    match adfree_core::assets::fetch_text(&url, 64 * 1024, std::time::Duration::from_secs(20)) {
        Ok(body) => println!("ok {} bytes in {:?}", body.len(), started.elapsed()),
        Err(e) => println!("failed: {e} after {:?}", started.elapsed()),
    }
}
