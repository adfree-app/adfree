package git.shin.adfree.desktop

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manifest is the only thing that makes Chrome start AdFree, so the tests
 * are about what a browser reads, not about the writer: the host name it must
 * answer to, the id allowed to call it, and a path that is executable.
 */
class NativeMessagingHostsTest {

    private val root: File = File(System.getProperty("java.io.tmpdir"), "adfree-nm-test-${System.nanoTime()}")
    private val host: File = File(root, "adfree-native-host").apply {
        parentFile.mkdirs()
        writeText("#!/bin/sh\n")
        setExecutable(true)
    }
    private val configHome: File = File(root, "config")

    @After
    fun clean() {
        root.deleteRecursively()
    }

    private fun browser(name: String = "google-chrome"): File = File(configHome, name).apply { mkdirs() }

    @Test
    fun `writes the manifest where chrome looks`() {
        browser()
        val written = NativeMessagingHosts.install(host, configHome)

        assertEquals(1, written.size)
        val manifest = File(configHome, "google-chrome/NativeMessagingHosts/${NativeMessagingHosts.HOST_NAME}.json")
        assertTrue(manifest.isFile)
        val text = manifest.readText()
        assertTrue(text.contains("\"path\": \"${host.absolutePath}\""))
        assertTrue(text.contains("chrome-extension://${NativeMessagingHosts.EXTENSION_ID}/"))
    }

    @Test
    fun `the manifest admits the assistant from every store it is published on`() {
        browser()
        NativeMessagingHosts.install(host, configHome)
        val text = File(configHome, "google-chrome/NativeMessagingHosts/${NativeMessagingHosts.HOST_NAME}.json")
            .readText()

        // The same extension has a different id in each store, and this key is
        // written into Edge, Brave and Vivaldi as well as Chrome. A manifest
        // listing only the Chrome id refuses the connection in the others, and
        // their popup then reports the app as not installed.
        for (id in NativeMessagingHosts.EXTENSION_IDS) {
            assertTrue("the manifest has to admit $id", text.contains("chrome-extension://$id/"))
        }
        assertTrue("and it is a list, not one string", text.contains("allowed_origins\": [\"chrome-extension://"))
    }

    @Test
    fun `installs into every browser present and none that is not`() {
        browser("google-chrome")
        browser("BraveSoftware/Brave-Browser")
        NativeMessagingHosts.install(host, configHome)

        assertTrue(File(configHome, "google-chrome/NativeMessagingHosts").isDirectory)
        assertTrue(File(configHome, "BraveSoftware/Brave-Browser/NativeMessagingHosts").isDirectory)
        // A browser that is not installed must not have a directory invented
        // for it: that is how a user ends up with a profile they never created.
        assertFalse(File(configHome, "vivaldi").exists())
    }

    @Test
    fun `a manifest from an older version is not installed`() {
        browser()
        NativeMessagingHosts.install(host, configHome)
        val manifest = File(configHome, "google-chrome/NativeMessagingHosts/${NativeMessagingHosts.HOST_NAME}.json")
        // What the previous release wrote: one id, everything else the same.
        manifest.writeText(
            manifest.readText().replace(
                Regex("\"allowed_origins\": \\[[^\\]]*\\]"),
                "\"allowed_origins\": [\"chrome-extension://${NativeMessagingHosts.EXTENSION_ID}/\"]",
            ),
        )
        assertTrue("the host is still there", host.canExecute())
        assertFalse(
            "a manifest that refuses every store but one has to be written again",
            NativeMessagingHosts.installedAndUsable(configHome),
        )
    }

    @Test
    fun `an upgrade repairs what the last version wrote`() {
        browser()
        NativeMessagingHosts.install(host, configHome)
        val manifest = File(configHome, "google-chrome/NativeMessagingHosts/${NativeMessagingHosts.HOST_NAME}.json")
        val repaired = manifest.readText()
        manifest.writeText(repaired.replace(Regex("\\[\\s*\"chrome-extension[^\\]]*\\]"), "[\"chrome-extension://x/\"]"))
        assertFalse(NativeMessagingHosts.installedAndUsable(configHome))

        // What the app does on every start when the manifest is not usable.
        NativeMessagingHosts.install(host, configHome)

        assertTrue(NativeMessagingHosts.installedAndUsable(configHome))
        assertEquals("and nothing but the origins changed", repaired, manifest.readText())
    }

    @Test
    fun `installed means the manifest names a host that can run`() {
        browser()
        assertFalse(NativeMessagingHosts.installedAndUsable(configHome))

        NativeMessagingHosts.install(host, configHome)
        assertTrue(NativeMessagingHosts.installedAndUsable(configHome))

        // A manifest pointing at a binary that is gone is not installed: the
        // browser would start nothing and the extension would report the app
        // as stopped, which is worse than saying it is not connected.
        host.delete()
        assertFalse(NativeMessagingHosts.installedAndUsable(configHome))
    }

    @Test
    fun `a stale manifest is reported as installed until the path is checked`() {
        browser()
        val manifest = File(configHome, "google-chrome/NativeMessagingHosts/${NativeMessagingHosts.HOST_NAME}.json")
        manifest.parentFile.mkdirs()
        manifest.writeText("""{"name":"x","path":"/nonexistent/host","type":"stdio"}""")

        assertFalse(NativeMessagingHosts.installedAndUsable(configHome))
    }

    /**
     * The Windows half, tested where it can be: the registry itself only exists
     * on Windows, but the two strings the value has to agree with a browser
     * about are pure, and getting either wrong means an extension that never
     * starts the host.
     */
    @Test
    fun `the windows key is the one chrome reads`() {
        assertEquals(
            "HKCU\\Software\\Google\\Chrome\\NativeMessagingHosts\\${NativeMessagingHosts.HOST_NAME}",
            NativeMessagingHosts.windowsKey("Google\\Chrome"),
        )
    }

    @Test
    fun `the windows manifest is one file in the app data directory`() {
        val dataDir = File(root, "data")
        val manifest = NativeMessagingHosts.windowsManifest(dataDir)
        assertEquals(
            File(dataDir, "native-messaging/${NativeMessagingHosts.HOST_NAME}.json").path,
            manifest.path,
        )
        // Outside the browser's profile on purpose: Windows finds it by
        // registry value, so it may live where the app already writes.
        assertTrue(manifest.path.startsWith(dataDir.path))
    }
}
