package git.shin.adfree.model

import git.shin.adfree.core.WarpBinding

/**
 * Which sentence the Cloudflare WARP row says.
 *
 * The client is the authority on all of it, and it answers in a shape that is
 * easy to read wrong: `installed` is about the machine, `connected` about the
 * tunnel, and only `proxy` mode also holds a port this proxy can forward
 * through. Keeping the decision here — instead of inside the composable — is
 * what lets each of those four answers be tested without a client installed.
 */
enum class WarpRow {
    /** No `warp-cli` on this machine. */
    Absent,

    /** A client that is up but tunnelling for the machine, not offering a port. */
    NotProxy,

    /** Proxy mode, tunnel down. */
    Disconnected,

    /** A SOCKS5 port on loopback that flows can be handed to. */
    Ready,
}

/** What the client's four answers come to, for the row. */
fun warpRow(state: WarpBinding?): WarpRow =
    when {
        state == null || !state.installed -> WarpRow.Absent
        !state.mode.equals("proxy", ignoreCase = true) -> WarpRow.NotProxy
        !state.connected -> WarpRow.Disconnected
        else -> WarpRow.Ready
    }

/**
 * Whether the routing block already points at this client.
 *
 * The port must match too: a stale 40000 against a client that now listens on
 * another port is a row that claims to be in use while every flow fails.
 */
fun warpInUse(
    state: WarpBinding?,
    saved: ProxySettings,
): Boolean {
    if (state == null || !state.usable) return false
    if (!saved.enabled) return false
    return normalizeProxyHost(saved.host) == WARP_HOST && saved.port == state.proxyPort.toInt()
}
