package git.shin.adfree.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import git.shin.adfree.core.WarpBinding
import git.shin.adfree.l10n.L10n
import git.shin.adfree.l10n.Strings
import git.shin.adfree.model.WarpRow
import git.shin.adfree.model.warpRow
import git.shin.adfree.platform.BackRegistry
import git.shin.adfree.platform.LocalAppInventory
import git.shin.adfree.platform.LocalBackRegistry
import git.shin.adfree.platform.LocalFileHost
import git.shin.adfree.platform.LocalPlatformHost
import git.shin.adfree.platform.LocalResumeTick
import git.shin.adfree.ui.screens.NetworkScreen
import git.shin.adfree.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test
import java.io.File

/**
 * The Cloudflare WARP row, on the real DNS screen and against the real core.
 *
 * The sentence it carries is this machine's to decide — a client installed and
 * disconnected here, no client at all on a build machine — so the assertion is
 * that the row renders the sentence `warpRow` says it should, not that a
 * particular client is present.
 */
@OptIn(ExperimentalTestApi::class)
class WarpRowUiTest {
    @Test
    fun theDnsScreenShowsWhatTheClientSays() {
        val dataRoot = File(System.getProperty("java.io.tmpdir"), "adfree-warp-${System.nanoTime()}")
        val container = DesktopContainer(dataRoot = dataRoot)
        check(container.engineAvailable) { "libadfree_core.so required: cargo build --release in rust/" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        container.start(scope)

        // Read once, from the same call the screen makes, so a client turned on
        // between the two would fail the assertion instead of hiding it.
        val warp: WarpBinding = container.viewModel.warpState()
        val expected =
            when (warpRow(warp)) {
                WarpRow.Absent -> L10n.get(Strings.network_warp_absent)
                WarpRow.NotProxy -> L10n.format(Strings.network_warp_mode, warp.mode)
                WarpRow.Disconnected -> L10n.get(Strings.network_warp_off)
                WarpRow.Ready -> L10n.format(Strings.network_warp_ready, warp.proxyPort.toInt())
            }
        // A desktop build cannot route through an upstream, so it must not offer
        // the button the phone can press; the note says so next to the status.
        val desktopCannotRoute = warp.usable && !container.platformHost.supportsSystemTunnel()
        val note = if (desktopCannotRoute) "\n" + L10n.get(Strings.network_warp_no_upstream) else ""

        runComposeUiTest {
            setContent {
                CompositionLocalProvider(
                    LocalPlatformHost provides container.platformHost,
                    LocalFileHost provides container.fileHost,
                    LocalAppInventory provides container.inventory,
                    LocalResumeTick provides MutableStateFlow(0L),
                    LocalBackRegistry provides BackRegistry(),
                ) {
                    MyApplicationTheme {
                        NetworkScreen(viewModel = container.viewModel, onBack = {})
                    }
                }
            }
            onNodeWithText(L10n.get(Strings.network_warp_title)).assertExists()
            onNodeWithText(L10n.get(Strings.network_warp_subtitle)).assertExists()
            onNodeWithTag("warp_status").assertExists()
            // The status line is the sentence the decision chose, and the reason
            // a disconnected client gave is appended to it.
            val shown =
                expected +
                    (
                        if (!warp.installed || warp.connected || warp.detail.isBlank()) {
                            ""
                        } else {
                            " — ${warp.detail}"
                        }
                    ) +
                    note
            onNodeWithText(shown).assertExists()
            // No button when this build would ignore the endpoint it installs.
            if (desktopCannotRoute || !warp.usable) {
                onNodeWithText(L10n.get(Strings.network_warp_use)).assertDoesNotExist()
                onNodeWithText(L10n.get(Strings.network_warp_current)).assertDoesNotExist()
            }
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
