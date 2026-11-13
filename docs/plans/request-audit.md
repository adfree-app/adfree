# Every outbound request, and where it can stall

A survey of the code paths that talk to the network, what bounds each one, and
what a user sees when the bound is hit. Ordered by how likely a stall is to be
noticed.

## Application requests

| Where | What | Bound | Was missing |
| --- | --- | --- | --- |
| `rust/src/assets.rs::fetch_text` | filter lists, userscripts, `@require` bodies | overall 60 s, 32 MiB cap, `BoundedResolver` 10 s for the name | **name resolution had no bound at all** — fixed |
| `AdGuardRepository.scriptStoreClient` | Greasy Fork search/pages, `addScript` body fetch | connect 15 s, read 20 s, call 60 s | call timeout was 0 (infinite) — fixed |
| `AppUpdateChecker.client` | GitHub releases API, APK download | connect 15 s, read 30 s, call 180 s | call timeout was 0 (infinite) — fixed |
| `AdGuardRepository` → `core_filter_fetch` / `core_script_fetch` / `core_services_fetch` | same `fetch_text` above | inherits | — |
| `platform/AssistantBridge` | answers the browser extension over a local socket | `read_frame`, no idle timeout | left as is: the popup holds the connection open for pushes, so an idle timeout would drop it |

## Core data path

| Where | What | Bound |
| --- | --- | --- |
| `proxy::dial_marked` | every upstream TCP dial (relay and MITM) | non-blocking connect + `CONNECT_TIMEOUT` poll + `SO_ERROR` |
| `proxy::tcp::resolve_authority` | plain-HTTP `CONNECT host:port` | **system `getaddrinfo`, unbounded** |
| `proxy/dns.rs::forward_query` | one query forwarded upstream for every client lookup | 5 s total, split over servers, copies re-sent every 700 ms, TCP last | before: one datagram, one 5 s wait, no retry |
| `upstream.rs` | DNS over UDP to the configured resolver (tun path) | poll with timeout, timeout counter exposed in stats |
| `dnssecure.rs` | DoT / DoH / DoQ | `BOOTSTRAP_TIMEOUT`, `IO_TIMEOUT` per socket |
| `udpproxy.rs` | UDP relay sockets | 5 s read timeout |
| `mitm/*` | TLS handshakes, relay pumps | poll-driven state machines, deadlines per flow |
| `bin/adfree_native_host.rs` | native messaging socket | connect 2 s, read `ANSWER_TIMEOUT` |
| `assistant.rs` | accept loop, `serve` per client | accept wakes on listener close; `serve` blocks in `read_frame` (one thread per idle client) |

## What the stalls look like, and what was done

**A name that never resolves.** `getaddrinfo` has no timeout; when the system
resolver is unreachable it can take minutes. The 60 s request timeout in
`fetch_text` starts at the first byte of socket I/O, so it did not cover
resolution — a list install could sit there looking frozen while nothing was
downloaded. `BoundedResolver` runs the lookup on a thread and waits 10 s, so the
outcome is "download failed with a message" instead of "nothing happens".

**A trickling response.** OkHttp's `readTimeout` is per read, so a server that
sends one byte every nineteen seconds never trips it and never finishes. Both
clients now set `callTimeout`; the userscript store's loading row and the update
check now end.

**A DNS query sent once and never again.** The listener that serves the
redirect rules had no retry: it sent the client's query upstream as a single
datagram and waited 5 s. Measured on this machine, all three candidate
resolvers (the configured AdGuard address, the router, 8.8.8.8) dropped about
half of them, so about a third of every client lookup got no answer at all —
which reads as "the network is broken for a while" and then heals. Forwarding
now re-sends within a 700 ms budget, splits the 5 s over the configured server
and the machine's own, and falls back to DNS over TCP. Measured through the
listener with the same lossy link: 20/20 answered, where 6/20 were answered
before.

Two more holes were closed in the same place:
* **A forwarded query coming back.** Under the redirect rules an *unmarked*
  upstream datagram is redirected into the listener again, and each hop spawned
  another thread: one lookup could become sixty-four. Forwarding sockets now
  register their own port, a query from that port is dropped, and a process
  that cannot mark its sockets forwards over TCP (port 53/TCP is not in the
  rules) instead of looping.
* The unmarked case is real: `SO_MARK` needs `CAP_NET_ADMIN`, which an
  unprivileged dev run does not have.

**Known, deliberate leftovers.**
* `resolve_authority` still uses the system resolver. It runs on that
  connection's own thread, so a slow answer delays one plain-HTTP request and
  nothing else. The filtered path (`resolve_plainly`) exists for excepted pages.
* An assistant client that connects and never writes holds a thread. It is a
  local socket, and the alternative (an idle timeout) would break the popup's
  long-lived connection.
* Downloads use the system resolver, not the app's own filtered one, on purpose:
  a filter list whose host appears in a blocked list must still be retrievable.
* The upstream resolver is whatever the app is configured with; when that
  server is the lossy one, the retry above is what keeps lookups answering.
  Switching it (`core_set_dns_server` / the settings screen) is the user's
  lever, not a fallback this code invents.

## Not the app: the free-pi connect failure

The intermittent `free-pi` connection failure is the agent's own API connection,
not anything in this repository: the app opens no connection to it, and the
failure does not appear in the filtering log. It recovers on its own, which is
the shape of a transient upstream failure rather than a client-side stall —
nothing here to fix.

It is, however, the same shape as the DNS finding above, and the agent runs on
this machine: its lookups go through this listener whenever the redirect rules
are installed (`meta skuid != 1000` exempts only root's traffic). A third of
them were being answered with silence, so a `free-pi` connect could fail without
anything being wrong at the far end. That is now the 20/20 case above.
