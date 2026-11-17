package git.shin.adfree.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import git.shin.adfree.l10n.L10n
import git.shin.adfree.l10n.Strings
import git.shin.adfree.platform.BackRegistry
import git.shin.adfree.platform.LocalAppInventory
import git.shin.adfree.platform.LocalBackRegistry
import git.shin.adfree.platform.LocalFileHost
import git.shin.adfree.platform.LocalPlatformHost
import git.shin.adfree.platform.LocalResumeTick
import git.shin.adfree.ui.AdGuardApp
import git.shin.adfree.ui.theme.MyApplicationTheme
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DNS filter set belongs to the DNS screen.
 *
 * A list that is only ever consulted on a name lookup was sitting on the
 * Filters tab among the URL and SNI lists, which reads as "this blocks
 * requests" when it cannot. This test drives the real app: the DNS screen must
 * carry the shipped filter and the rule editor, and the Filters tab must no
 * longer carry either.
 */
@OptIn(ExperimentalTestApi::class)
class DnsFiltersUiTest {

    @Test
    fun the_dns_screen_owns_the_dns_filters() {
        val dataRoot = File(System.getProperty("java.io.tmpdir"), "adfree-dns-${System.nanoTime()}")
        val container = DesktopContainer(dataRoot = dataRoot)
        check(container.engineAvailable) { "libadfree_core.so required: cargo build --release in rust/" }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        container.start(scope)

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
                        AdGuardApp(viewModel = container.viewModel)
                    }
                }
            }

            waitUntilAtLeastOneExists(hasText(L10n.get(Strings.app_name)), 30_000)

            // The DNS module on the Protection landing page opens the DNS
            // screen; the rail is the route this app has always used.
            onNodeWithText(L10n.get(Strings.nav_protection)).performClick()
            waitUntilAtLeastOneExists(hasText(L10n.get(Strings.protection_subtitle)), 30_000)
            onNodeWithText(L10n.get(Strings.home_module_dns_title)).performClick()
            waitUntilAtLeastOneExists(hasText(L10n.get(Strings.filters_dns_title)), 30_000)

            // The shipped filter and the rule editor are both on this screen.
            onNodeWithTag("dns_filters_section").assertExists()
            onNodeWithTag("dns_user_rules_row").assertExists()
            onNodeWithText(L10n.get(Strings.dns_filters_default_name)).assertExists()
            onNodeWithText(L10n.get(Strings.dns_rules_user_title)).assertExists()

            // Captured before the sheet opens: the sheet is a second compose
            // root, and a capture of "the root" cannot choose between them.
            val image = onRoot().captureToImage().toAwtImage()
            ImageIO.write(image, "png", File("/tmp/adfree_dns_filters.png"))

            // The row opens the editor.
            onNodeWithTag("dns_user_rules_row").performClick()
            waitUntilAtLeastOneExists(hasText(L10n.get(Strings.rules_tab_raw)), 30_000)
        }

        val lists = container.viewModel.uiState.value.filterLists
        val dns = lists.filter { it.isDnsOnly }
        assertTrue(
            "the shipped AdGuard DNS filter is missing: ${lists.map { it.id }}",
            dns.any { it.subscriptionUrl == "https://filters.adtidy.org/android/filters/15_optimized.txt" },
        )
        assertTrue(
            "the DNS rule list is missing: ${lists.map { it.id }}",
            dns.any { it.customRules.isEmpty() && it.name.isNotBlank() } && lists.any { it.id == "builtin_dns_rules" },
        )
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
