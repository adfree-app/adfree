//! The Windows redirect as the application sees it: on, off, and the counters.
//!
//! On Linux the "redirect" is a rule table the platform layer installs, so this
//! module has nothing to say there and says so: [`start`] answers with a
//! sentence, not a silent success. On Windows it owns the driver loop — one
//! thread, two handles, one table — and hands the UI the numbers the tray
//! tooltip shows.

use std::sync::Mutex;

/// What the redirect is doing, as the UI asks it.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct RedirectStatus {
    pub running: bool,
    pub packets_seen: u64,
    pub live_flows: u64,
}

struct Running {
    stop: Box<dyn Fn() + Send + Sync>,
    status: Box<dyn Fn() -> (u64, usize) + Send + Sync>,
    /// One thread per direction, plus the one that says what they are doing.
    threads: Vec<std::thread::JoinHandle<()>>,
}

static RUNNING: Mutex<Option<Running>> = Mutex::new(None);

/// Starts the redirect towards the local proxy and resolver.
///
/// Idempotent: a second call while it runs is not an error, because the caller
/// is usually "protection went on" and protection can be asked for twice.
pub fn start(proxy_port: u16, dns_port: u16) -> Result<(), String> {
    #[cfg(windows)]
    {
        use crate::sys::windivert;
        use crate::winnat::Endpoint;
        use std::net::{IpAddr, Ipv4Addr};
        use std::sync::Arc;

        let mut slot = RUNNING.lock().map_err(|_| "redirect lock poisoned")?;
        if slot.is_some() {
            return Ok(());
        }
        let proxy = Endpoint::new(IpAddr::V4(Ipv4Addr::LOCALHOST), proxy_port);
        let dns = Endpoint::new(IpAddr::V4(Ipv4Addr::LOCALHOST), dns_port);
        let redirector = windivert::Redirector::start(proxy, dns)?;
        let mut threads = Vec::new();
        for (name, pass) in [
            ("adfree-redirect-out", windivert::run_outbound as fn(&Arc<_>)),
            ("adfree-redirect-in", windivert::run_inbound),
        ] {
            let inner = Arc::clone(&redirector);
            threads.push(
                std::thread::Builder::new()
                    .name(name.to_string())
                    .spawn(move || pass(&inner))
                    .map_err(|e| format!("cannot start the {name} thread: {e}"))?,
            );
        }
        let watcher = Arc::clone(&redirector);
        threads.push(
            std::thread::Builder::new()
                .name("adfree-redirect-watch".to_string())
                .spawn(move || watch(&watcher))
                .map_err(|e| format!("cannot start the redirect watchdog: {e}"))?,
        );
        let stop_target = Arc::clone(&redirector);
        let status_target = Arc::clone(&redirector);
        *slot = Some(Running {
            stop: Box::new(move || stop_target.stop()),
            status: Box::new(move || {
                (status_target.packets_seen(), status_target.live_mappings())
            }),
            threads,
        });
        Ok(())
    }
    #[cfg(not(windows))]
    {
        let _ = (proxy_port, dns_port);
        Err("the driver redirect exists only on Windows; Linux uses nftables".to_string())
    }
}

/// Says, in the log, what the redirect is doing — once when it has had time to
/// see its first packet, and then every few seconds after that.
///
/// An app that intercepts nothing looks exactly like an app that intercepts
/// everything: the window says "protected", the driver is loaded, and the only
/// difference is whether packets move. This line is that difference, and the
/// first one is the important one.
#[cfg(windows)]
fn watch(redirector: &std::sync::Arc<crate::sys::windivert::Redirector>) {
    let mut first_report = true;
    while !redirector.stopped() {
        std::thread::sleep(std::time::Duration::from_secs(if first_report { 10 } else { 60 }));
        if redirector.stopped() {
            break;
        }
        crate::logbuf::push_error(&crate::sys::windivert_filter::report(
            redirector.packets_seen(),
            redirector.redirected_count(),
            redirector.dropped_count(),
            redirector.live_mappings(),
        ));
        first_report = false;
    }
}

/// Ends the redirect and waits for its threads. Safe to call when it is not
/// running, which is what "protection off" turns into.
pub fn stop() {
    let running = {
        let mut slot = match RUNNING.lock() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        slot.take()
    };
    if let Some(running) = running {
        (running.stop)();
        // The loops may still be inside the driver's `recv`; closing the handles
        // is what releases them.
        for thread in running.threads {
            let _ = thread.join();
        }
    }
}

pub fn status() -> RedirectStatus {
    let slot = match RUNNING.lock() {
        Ok(g) => g,
        Err(poisoned) => poisoned.into_inner(),
    };
    match slot.as_ref() {
        Some(running) => {
            let (packets_seen, live_flows) = (running.status)();
            RedirectStatus {
                running: true,
                packets_seen,
                live_flows: live_flows as u64,
            }
        }
        None => RedirectStatus {
            running: false,
            packets_seen: 0,
            live_flows: 0,
        },
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn on_a_platform_without_the_driver_starting_says_so() {
        #[cfg(not(windows))]
        {
            assert!(start(3129, 5300).is_err());
            assert!(!status().running);
            // Stopping something that never started is not an error.
            stop();
        }
    }

    #[test]
    fn status_is_quiet_before_and_after_stop() {
        stop();
        let s = status();
        assert!(!s.running);
        assert_eq!(s.packets_seen, 0);
        assert_eq!(s.live_flows, 0);
    }
}
