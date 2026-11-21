package git.shin.adfree.model

import git.shin.adfree.core.WarpBinding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WARP row's four answers, and the one that installs an endpoint.
 *
 * These are the decisions the screen makes from `warp-cli`'s own report, so they
 * are checked here rather than through a client that may or may not be installed
 * on the machine running the tests.
 */
class WarpSummaryTest {
    private fun state(
        installed: Boolean = true,
        connected: Boolean = true,
        mode: String = "proxy",
        port: Int = 40000,
        detail: String = "",
    ) = WarpBinding(
        installed = installed,
        connected = connected,
        mode = mode,
        proxyPort = port.toUShort(),
        detail = detail,
        usable = installed && connected && mode.equals("proxy", ignoreCase = true),
    )

    @Test
    fun aMachineWithoutTheClientSaysSo() {
        assertEquals(WarpRow.Absent, warpRow(null))
        assertEquals(WarpRow.Absent, warpRow(state(installed = false, connected = false)))
    }

    /**
     * A connected `warp` client tunnels this machine's own traffic and holds no
     * port. Calling that ready would offer a button that installs an endpoint
     * nothing is listening on.
     */
    @Test
    fun aTunnelWithNoPortIsNotReady() {
        assertEquals(WarpRow.NotProxy, warpRow(state(mode = "Warp")))
        assertEquals(WarpRow.NotProxy, warpRow(state(mode = "doh")))
    }

    @Test
    fun proxyModeWithTheTunnelDownIsNotReady() {
        assertEquals(WarpRow.Disconnected, warpRow(state(connected = false)))
    }

    @Test
    fun aConnectedProxyClientIsReady() {
        assertEquals(WarpRow.Ready, warpRow(state()))
        // The spelling this machine's client uses for proxy mode.
        assertEquals(WarpRow.Ready, warpRow(state(mode = "WarpProxy")))
        assertEquals(WarpRow.Ready, warpRow(state(mode = "Proxy")))
    }

    @Test
    fun theRowSaysInUseOnlyForThisClientAndThisPort() {
        val client = state(port = 40000)
        val pointing = ProxySettings(enabled = true, host = "127.0.0.1", port = 40000)
        assertTrue(warpInUse(client, pointing))

        // A different port means the flows go somewhere the client is not
        // listening, so the row must not claim to be in use.
        assertFalse(warpInUse(state(port = 41234), pointing))
        // Off is off, whatever the fields say.
        assertFalse(warpInUse(client, pointing.copy(enabled = false)))
        // A name is not the loopback address the client binds.
        assertFalse(warpInUse(client, pointing.copy(host = "warp.local")))
        // A client that is not ready cannot be in use.
        assertFalse(warpInUse(state(connected = false), pointing))
    }
}
