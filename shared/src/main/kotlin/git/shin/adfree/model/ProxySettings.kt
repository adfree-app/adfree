package git.shin.adfree.model

import java.net.InetAddress

/**
 * Which protocol the upstream proxy speaks.
 *
 * SOCKS5 carries TCP and UDP; an HTTP proxy carries TCP only, through
 * `CONNECT`. The difference is not cosmetic: UDP flows have nowhere to go
 * through an HTTP proxy, so they stay direct and say so.
 */
enum class ProxyProtocol(
    val id: String,
) {
    SOCKS5("socks5"),
    HTTP("http"),
    ;

    companion object {
        fun of(id: String): ProxyProtocol = entries.firstOrNull { it.id == id } ?: SOCKS5
    }
}

/**
 * Outbound proxy endpoint for TCP flows (routing mode).
 *
 * The host stays an IP literal because the core dials it from the packet loop:
 * resolving a name there would stall every flow. Credentials are optional and
 * only sent when both halves are present — RFC 1929 for SOCKS5, Basic
 * `Proxy-Authorization` for HTTP.
 */
data class ProxySettings(
    val enabled: Boolean = false,
    val automatic: Boolean = true,
    val host: String = "",
    val port: Int = 1080,
    val username: String = "",
    val password: String = "",
    val protocol: ProxyProtocol = ProxyProtocol.SOCKS5,
) {
    /** SOCKS5 is the only one of the two that can carry UDP. */
    val carriesUdp: Boolean
        get() = protocol == ProxyProtocol.SOCKS5

    /** Ready to route: a literal address and a real port. */
    val isUsable: Boolean
        get() = normalizeProxyHost(host) != null && port in 1..65535
}

/**
 * Where the desktop's own filtering listeners are raised — AdGuard for Windows'
 * shape: a local HTTP(S) proxy and a local DNS server that the machine's
 * traffic is redirected onto.
 *
 * Both numbers are the user's to move, because a machine that already runs
 * something on them has to be worked around rather than reformatted. Below
 * [MIN_LISTENER_PORT] a bind needs root, and a listener that cannot bind is a
 * filter that quietly relays, so a pair that does not fit is refused rather
 * than clamped behind the user's back.
 *
 * [filterLocalhost] is AdGuard for Windows' "Filter localhost": on, this
 * machine's own conversations are filtered like any other; off, they keep
 * whatever they were talking to — a local dev server, the stub resolver —
 * and the redirect rules step aside for loopback destinations.
 */
data class LocalProxySettings(
    val tcpPort: Int = 3129,
    val dnsPort: Int = 5300,
    val filterLocalhost: Boolean = true,
) {
    /** Two listeners, two numbers, both ours to take. */
    val isUsable: Boolean
        get() =
            tcpPort in MIN_LISTENER_PORT..MAX_PORT &&
                dnsPort in MIN_LISTENER_PORT..MAX_PORT &&
                tcpPort != dnsPort

    companion object {
        /** Must match `MIN_LISTENER_PORT` in `rust/src/proxy/mod.rs`. */
        const val MIN_LISTENER_PORT = 1024
        const val MAX_PORT = 65535
    }
}

/**
 * Where the Cloudflare WARP client listens in `proxy` mode: its SOCKS5 server is
 * bound to loopback and nowhere else, so a name here would be a dial that can
 * never connect.
 */
const val WARP_HOST: String = "127.0.0.1"

/**
 * The SOCKS5 host the core can dial: an IPv4/IPv6 literal, never a name.
 *
 * Brackets from `[::1]` are stripped so the value is exactly what the core
 * parses. `null` means the text is not an address.
 */
fun normalizeProxyHost(raw: String): String? {
    val host = raw.trim().removeSurrounding("[", "]")
    return host.takeIf(::isIpLiteral)
}

fun isIpLiteral(host: String): Boolean {
    val v4 =
        host.matches(Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")) &&
            host.split('.').all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true }
    if (v4) return true
    if (!host.contains(':')) return false
    if (!host.matches(Regex("^[0-9a-fA-F:.]+$"))) return false
    // Let the platform validate IPv6 compression; a numeric-only host that
    // fails here is malformed, not a name to resolve.
    return runCatching { InetAddress.getByName(host) }.isSuccess
}
