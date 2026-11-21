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

The tree spoke SOCKS5 in one direction — `rust/src/socks5.rs` is the server
the app offers to other programs — and the *other* direction already existed
as `ProxyEndpoint`: SOCKS5 upstream for TCP and UDP (`socks5.rs`), selected in
the Network screen and carried by the relay's `handshake` slot.

**Built since this plan was written:** HTTP `CONNECT` upstream. `ProxyKind`
joins `ProxyEndpoint` (`socks5`/`http`), `rust/src/httpconnect.rs` speaks the
`CONNECT` half — status-line only, Basic `Proxy-Authorization`, no hidden
chunking — behind one `UpstreamHandshake` the relay already knows how to
drive, and a UDP flow has nowhere to go through an HTTP proxy, so it stays
direct and the log says why.

**Seam.** `mitm/relay.rs` opens the outbound socket; `mitm/tls.rs` wraps it.
An upstream transport is a replacement for the connect half only:
```
struct Direct;
struct Socks5Upstream { addr, credentials }
struct HttpConnectUpstream { addr, credentials }   // built
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

## 4. Cloudflare WARP

WARP is a tunnel, and the client that owns it on each platform is the only
thing that speaks its protocol. On Linux, macOS and Windows that client can be
told to run in `proxy` mode, where the tunnel it already established is offered
to anything that connects to a loopback SOCKS5 port. That is the same shape as
section 2's upstream, so the integration is a settings row, not a transport:
read the client's own answer (`warp-cli status`, `warp-cli settings`), and when
it says `proxy` and `Connected`, fill `127.0.0.1:<port>` into the routing block.

**Landed:** `rust/src/warp.rs` (parsers over the client's text, five tests),
`core_warp_state()`, and a card above the routing block on the Network screen.
Nothing is spawned until that screen opens; the card's button re-reads the
client before it installs the endpoint, because a card drawn a minute ago must
not point flows at a port that has since been turned off.

**What the in-process version would cost.** The pieces exist as crates:
`boringtun` (Cloudflare's own userspace WireGuard, BSD-3) for the protocol,
`warp-wireguard-gen` for registration, `wireguard-netstack` for a TCP stack over
the tunnel. Three things rule it out as a commit:

- **Licence.** `warp-wireguard-gen` is GPL-3.0; this tree is Apache-2.0, so
  linking it would relicense the whole app. The registration half would have to
  be written here (it is a few requests and an X25519 keypair), which is the
  small part.
- **The stack between.** WireGuard carries IP packets, and this proxy forwards
  TCP streams. Between them sits a userspace TCP/IP stack — `wireguard-netstack`
  or `smoltcp` — with its own timer loop, window handling and teardown. That is
  a project, not a module.
- **WARP's default is no longer WireGuard.** The client on this machine reports
  `WARP tunnel protocol: MASQUE` — HTTP/3. An in-process client built on
  `boringtun` would cover only the older path and be at Cloudflare's mercy for
  the rest.

**UDP.** In proxy mode the client's SOCKS5 does accept `UDP ASSOCIATE`, so the
UDP half of section 2 works through it unchanged when the client supports it.

**The desktop had no upstream at all, and that was a lie in the UI.** The tunnel
engine reads `Config::proxy`; the desktop listeners (`proxy/tcp.rs`) dialled the
destination directly, so an endpoint saved on a desktop was stored, displayed as
"in use", and never dialled. Turning a WARP client to `proxy` mode is what made
it visible from outside: `curl` through the app still left with this machine's
own address while the card said the client was in use.

**Landed:** `rust/src/proxy/upstream.rs` drives the very handshake the engine
uses (`UpstreamHandshake`, SOCKS5 or `CONNECT`) over a blocking socket, once per
flow, and hands the stream back to the pump in the non-blocking regime it
expects. Three things it keeps on purpose:

- **An upstream is read per flow, not at start.** `ADFREE_CORE_LIB` taught the
  lesson the other way round: a value captured at start is a value that goes
  stale silently. The endpoint comes from the state the settings save into.
- **A refusal is a refusal.** When the endpoint refuses the handshake the flow
  ends — it does not fall back to a direct dial. Falling back would send traffic
  the user asked to be carried by the endpoint out in the clear, which is the one
  failure mode a proxy setting must never have.
- **Automatic routing still bypasses private destinations**, through the same
  `bypasses_automatic` the relay uses, so a LAN address never leaves the machine.

What is still outside: the DNS half of the desktop still resolves through the
system resolver, so lookups do not travel through the endpoint. With WARP in
`proxy` mode that means resolution happens on this side of the tunnel.

## 5. AdGuard VPN

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
4. WARP: the row is in; switching the client's mode and starting it from inside
the app is the next step, and it is a system-wide change that needs its own
consent prompt.

Each of these lands alone; none of them needs the next one to be useful.
