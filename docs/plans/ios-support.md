# iOS: full MITM filtering, Android UI reused

Decisions, in the order they were settled, and what is already in the tree.

## What iOS gives us

| Needed | Android | iOS |
| --- | --- | --- |
| tunnel with a route to `0.0.0.0/0` | `VpnService` + `Builder` | `NEPacketTunnelProvider` + `NEPacketTunnelNetworkSettings` |
| packet I/O | a `tun` **descriptor** | `NEPacketTunnelFlow.readPackets` / `writePackets` — packets, **no descriptor** |
| outgoing sockets bypass the tunnel | `VpnService.protect(fd)` | automatic: the extension's own sockets are not captured by its own tunnel |
| always-on | `SUPPORTS_ALWAYS_ON` | `NEOnDemandRule` (+ MDM "Always-On VPN" on supervised devices) |
| quick toggle | QS tile | Shortcuts / Control Centre control (iOS 18) |
| per-app rules (`$app=`, firewall exclusions) | `addDisallowedApplication`, uid→package table | **not available**: a packet tunnel carries no app attribution, and per-app routing is MDM-only |
| MITM trust | install CA into the user/system store | **profile** (`.mobileconfig`) + *Settings → General → About → Certificate Trust Settings → Enable Full Trust*. No silent install without MDM. |

So the filtering stack — TLS termination, cosmetic/scriptlet injection, DNS,
HTTP/3, the log — is the same code. The only missing primitive was packet I/O
without a descriptor, and that is now in the core.

## Done (this tree)

* `Core::start_pull`, `inject_packet`, `poll_packets` (`rust/src/engine.rs`):
  the same worker set as `start(fd)` minus the descriptor reader; `write_raw`
  queues into the pull queue instead of writing to an fd. Packets leave as
  `u16` big-endian length + bytes, one buffer per call.
* uniffi surface for the Swift host: `core_start_packets()`,
  `core_packet_in(packet)`, `core_packets_out()`.
* `core_ca_mobileconfig()`: the root CA as an unsigned Apple profile, with a
  UUID derived from the certificate so reinstalling replaces rather than
  duplicates. The description tells the user about the Full Trust step,
  because a profile that is installed and never trusted is inert and looks
  like a broken app.
* Bindings regenerated into `shared/src/main/kotlin/git/shin/adfree/core/adfree.kt`.

## The Swift side (not written here; needs macOS)

1. App target with an `NEPacketTunnelProvider` extension.
2. Provider start: `setTunnelNetworkSettings` (address `10.0.2.15/32`, DNS
   `10.0.2.3`, routes `0.0.0.0/0` and `::/0`, MTU 1500), then
   `core_start_packets()` and read loop:

   ```swift
   packetFlow.readPackets { packets, _ in
       for packet in packets { corePacketIn(packet: packet) }
       let batch = corePacketsOut()          // u16 length + packet, repeated
       packetFlow.writePackets(self.parse(batch), withProtocols: self.protocols(of: batch))
   }
   ```

   `corePacketsOut()` must also be called on a timer (~10 ms) and whenever
   `readPackets` fires: most answers are produced by the forwarder thread after
   the inbound packet has been handled, so they are not in the first batch.
3. Certificate: show `core_ca_mobileconfig()` through
   `UIApplication.shared.open(URL(string: "data:application/x-apple-aspen-config;base64,...")!)`
   — or write it to a file and open that; data URLs are not reliable for
   profiles, so a file in the app's Documents opened with `UIDocumentInteractionController`
   is the safer path.
4. Entitlement `com.apple.developer.networking.networkextension` with
   `packet-tunnel-provider`, requested from Apple. This is the part with a
   waiting period, so it goes first.

## The UI: Compose Multiplatform, which means a KMP conversion

`shared/` is a **Kotlin/JVM** module today (`plugins { kotlin.jvm, jetbrains.compose }`).
Compose 1.8.2 already ships iOS artifacts, so the screens themselves need no
rewrite — but the module has to become Kotlin Multiplatform:

1. Apply `kotlin("multiplatform")` in `shared/build.gradle.kts`; keep `jvm()` for
   `:desktop`, add `androidTarget()` for `:app`, add `iosArm64()`,
   `iosSimulatorArm64()`, `iosX64()`.
2. Move the JVM-only dependencies into the `jvmMain` (and `androidMain`) source
   sets: `jna` (desktop window/tray), `okhttp` (HTTP + OTA), DataStore.
   For iOS, `okhttp` has to become `ktor-client-darwin` behind the same façade
   the repository already uses, or the download paths stay `jvmMain`-only.
3. `compose.runtime/foundation/ui/material3` change from `compileOnly` desktop
   artifacts to the multiplatform `compose.*` accessors, with each host bringing
   its runtime as today.
4. Add `iosMain/…/MainViewController.kt`:

   ```kotlin
   fun MainViewController(): UIViewController = ComposeUIViewController { AdFreeApp() }
   ```

   The Swift app hosts it with `UIViewControllerRepresentable` (or as the root
   view controller).
5. The Android-only entry points (`VpnService`, QS tile, boot receiver,
   automation receiver) stay in `:app`; the iOS equivalents are Swift.

Cost: mechanical but wide — every `java.*`/`javax.*` import in `shared` has to
become `expect`/`actual` or be moved to a host source set. This is the real
price of "reuse the Kotlin UI", and it is why the packet bridge was written
first: the pipeline works on Linux, the UI conversion does not.

## Constraints to design against

* **Network Extension memory**: iOS kills an extension well below the app's
  budget (~50 MB observed, and reported as low as 15 MB for some types). The
  filter engine alone measured 62 MB with the default lists
  (`rust/examples/engine_mem.rs`), so the iOS build ships a reduced list set
  (no OISD big, no ag-tracking) and the app must fail soft: if the extension is
  killed, protection reports "off" rather than looping.
* **MITM is opt-in**: without the profile, HTTPS stays opaque. Keep DNS + SNI
  blocking working in that state, so the app is useful before the certificate
  is installed.
* **Per-app rules stay unavailable** on iOS; the UI should hide them rather
  than show switches that do nothing.
* **Build host**: Kotlin/Native and Xcode are macOS-only. `rustc` can cross-
  compile to `aarch64-apple-ios` from Linux, but the Swift/Kotlin side needs
  `macos-14` CI.
