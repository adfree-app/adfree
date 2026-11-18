# Tailscale, AdGuard VPN and a request inspector

Three requests, scoped against what this tree already has. Each section says
where the seam is, what is missing, and what the change costs, so the work can
be cut into commits that each leave the tree working.

## 1. The request inspector (started)

The filtering log answers a decision: blocked or allowed, by which rule, for
which app. It keeps no bytes. A Wireshark-shaped view answers a different
question — what the connection actually carried — and that is the one thing the
engine currently throws away.

**The exchange already exists.** `mitm/proxy.rs` reads the request head, keeps
the URL and media type in `ResponseEdit`, and parses the answer's head into
`http::ResponseHead`. That is the exact pair a record needs.

**What landed** (`rust/src/capture.rs`, `api.rs`, `mitm/proxy.rs`):

| piece | state |
| --- | --- |
| `capture::record_request/record_response` over a bounded ring (300 records, 4 KiB per head) | done |
| switch read once per request, off by default | done |
| hooked at the request head and the answer's head, both behind the switch | done |
| `core_capture_set_enabled`, `core_capture_records`, `core_capture_clear`, `core_capture_count` | done |
| bodies, transfer decoding, gzip | not started |

**Why bodies are not in it yet.** A body is not a buffer in this tree: it may be
length-framed, chunked, `Content-Encoding`d, streamed through
`http::DechunkState`, and rewritten on the way out. Copying it means running the
same decode path twice, on the packet path, while a window is open. The honest
shape is a second switch (`bodies`) that reuses the stream state machine's
output rather than re-parsing the wire, and a size cap well under what the
inspector shows. Until that exists, the inspector shows heads, statuses and
timings — which is most of what a "which request went where" question needs.

**Cost while off.** One relaxed atomic load per request. Nothing is allocated,
nothing is copied.

**Cost while on.** Up to 8 KiB per record (both heads), 300 records, so a cap of
about 2.4 MiB. The ring drops the oldest, so an open window on a busy page stops
at the cap instead of growing.

**UI, next commit.** A screen in the shared Compose shell: a list of records
(url, status, time), a detail pane showing the two heads verbatim, and the
switch tied to the screen's own lifetime — open turns it on, close turns it off,
which is exactly what makes it free when nobody is looking. The seam is already
reachable: `coreCaptureSetEnabled` / `coreCaptureRecords` are on the same
generated bridge as `coreDrainEvents`.

## 2. Custom proxy protocols (SOCKS5, HTTP CONNECT upstream)

The tree already speaks SOCKS5 in one direction: `rust/src/socks5.rs` is the
server the app offers to other programs. Routing *upstream* through a proxy is
the other direction and does not exist: every connection the engine makes is
direct, resolved locally by `dns.rs`/`upstream.rs`.

**Seam.** `mitm/relay.rs` opens the outbound socket; `mitm/tls.rs` wraps it.
An upstream transport is a replacement for the connect half only, so it is one
trait with two implementations:

```
trait Upstream { fn connect(&self, host, port) -> Result<TcpStream> }
struct Direct;
struct Socks5Upstream { addr, credentials }
struct HttpConnectUpstream { addr, credentials }
```

**What has to change beyond the trait.** DNS: a name that an upstream proxy
resolves must not be resolved here first, or the app leaks the very query it
was asked to hide — the plan carries a name, not an address. Stats and the log
must name the upstream, otherwise every row silently claims it went direct.
Blocked hosts: a rule still has to match a name, which means the decision
happens before the tunnel is chosen.

**Estimate.** ~600 lines, one new module, three call sites, a settings block and
four strings per language.

## 3. Tailscale

Tailscale is not a proxy in the sense above; it is a userspace VPN that owns
routes. Two useful halves, and only one of them is small:

1. **Exit node as an upstream.** When the machine runs `tailscaled` with an exit
   node, all traffic already leaves through the tailnet interface — the engine
   needs nothing. What it cannot do is *pick* an exit node or show which one is
   in use. That is `tailscale status --json` and `tailscale set --exit-node=…`
   behind a settings row: a status read, a command, four strings. Small.
2. **Tailscale inside the app (userspace `netstack`).** Making the app itself a
   tailnet node means a userspace TCP/IP stack in front of the proxy — a
   different order of work (`tsnet` has no Rust port of that maturity) and a key
   that has to live in the app's own store.

**Recommendation.** Ship (1): read the exit node from `tailscale status --json`,
offer it as the upstream in section 2, and treat "no tailscaled" as a row that
says so. (2) is a project, not a commit.

## 4. AdGuard VPN

AdGuard VPN's protocol is not published, and its client is closed. Interoperating
means either:

- **Reverse-engineering the wire protocol** — undocumented, unlicensed, and it
  breaks on their release schedule. Not something to build a feature on.
- **Driving their client as an upstream** — AdGuard VPN, like Tailscale, installs
  a tunnel interface. Where it is active, the engine's outbound connections
  already leave through it; what it can do is detect that interface and say so
  in the tunnel row, and refuse to claim it is filtering traffic it never sees.

**What is honest to build here.** A row that detects a foreign VPN interface
(`tailscale0`, `tun0`, the AdGuard VPN adapter on Windows) and states the
relationship plainly: AdFree filters on top of it, or it does not see the
traffic at all. That is a detection, not an integration, and it is the difference
between a claim and the truth.

## Order

1. Inspector bodies behind a second switch, and the inspector screen.
2. Upstream trait with SOCKS5, then the exit-node row that feeds it.
3. Interface detection for Tailscale and AdGuard VPN, as status only.

Each of these lands alone; none of them needs the next one to be useful.
