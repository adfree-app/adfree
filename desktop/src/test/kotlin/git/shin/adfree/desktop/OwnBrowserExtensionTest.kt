package git.shin.adfree.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The extension is a client of this app and holds no rules, so nothing here
 * checks what it would block — what it checks is that the app can actually hand
 * it over: every file in the bundle is on the classpath, the manifest names the
 * icons that are there, and the write lands where the UI says it will.
 *
 * The failure this prevents is quiet: `bun run build` renames nothing today, so
 * a file list that drifts from the bundler's output would install a folder a
 * browser refuses to load, with a green build.
 */
class OwnBrowserExtensionTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun everyFileInTheBundleIsOnTheClasspath() {
        for (name in OWN_EXTENSION_FILES) {
            assertNotNull(
                "the app jar carries no /extension/$name",
                DesktopPlatformHost::class.java.getResource("/extension/$name"),
            )
        }
    }

    @Test
    fun theManifestNamesTheIconsTheBundleShips() {
        val manifest = String(
            DesktopPlatformHost::class.java.getResourceAsStream("/extension/manifest.json")!!
                .use { it.readBytes() },
        )
        for (icon in OWN_EXTENSION_FILES.filter { it.startsWith("icons/") }) {
            assertTrue("manifest.json does not mention $icon", manifest.contains(icon))
        }
    }

    @Test
    fun theBundleShipsTheIconsOfTheApp() {
        // Same sizes as the app's own icon set: a browser asks for these four
        // and nothing else, and a missing one shows a generic puzzle piece.
        for (size in listOf(16, 32, 48, 128)) {
            val bytes = DesktopPlatformHost::class.java
                .getResourceAsStream("/extension/icons/icon-$size.png")!!
                .use { it.readBytes() }
            assertTrue("icon-$size.png is not a PNG", bytes.size > 8 && bytes[1] == 'P'.code.toByte())
        }
    }

    @Test
    fun installingWritesEveryFileIntoTheFolder() {
        val folder = File(temporary.root, "adfree/extension")

        assertNull("a bundle on the classpath writes without complaint", DesktopPlatformHost().installOwnExtensionInto(folder))

        for (name in OWN_EXTENSION_FILES) {
            val written = File(folder, name)
            assertTrue("$name was not written", written.isFile)
            assertTrue("$name is empty", written.length() > 0)
        }
        assertEquals(OWN_EXTENSION_FILES.size, folder.walkTopDown().filter { it.isFile }.count())
    }

    @Test
    fun theFolderTheUiShowsIsUnderTheUsersDataHome() {
        val folder = DesktopPlatformHost().ownBrowserExtensionFolder()

        assertTrue("an absolute path", folder.startsWith("/"))
        assertTrue(folder, folder.endsWith("/adfree/extension"))
    }
}
