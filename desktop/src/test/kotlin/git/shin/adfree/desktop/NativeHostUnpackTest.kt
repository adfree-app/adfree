package git.shin.adfree.desktop

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test

/**
 * The host that has to come out of the jar.
 *
 * AdGuard's browser extension is what a user sees: when its popup cannot start
 * this binary it says AdFree is not installed, which on a machine that is
 * filtering reads as the app being broken. An installed package has no
 * repository beside it and no `app/resources`, so the host has to travel inside
 * the application jar and be written out where Chrome can start it — the same
 * road the core library takes.
 */
class NativeHostUnpackTest {

    @Test
    fun theHostComesOutOfTheJarWhenNothingIsInstalled() {
        assumeFalse("the packaged name differs on Windows", isWindowsHost())
        val unpacked = NativeMessagingHosts.unpackedHostBinary("adfree-native-host.jar-test")
        assertNotNull("the resource must be found and written", unpacked)
        val file = unpacked!!
        assertTrue("the host has to be a file a browser can start", file.isFile)
        assertEquals("#!/bin/sh\necho host\n", file.readText())
        assertTrue("and one the user can execute", file.canExecute() || !isWindowsHost())
    }

    @Test
    fun theHostIsWrittenOnceAndReused() {
        assumeFalse("the packaged name differs on Windows", isWindowsHost())
        val first = NativeMessagingHosts.unpackedHostBinary("adfree-native-host.jar-test")
        val second = NativeMessagingHosts.unpackedHostBinary("adfree-native-host.jar-test")
        assertEquals("the same path both times", first, second)
        assertEquals(
            "and the bytes are the resource's",
            File(first!!.path).length(),
            NativeHostUnpackTest::class.java.getResourceAsStream("/adfree-native-host.jar-test")!!
                .use { it.readBytes() }.size.toLong(),
        )
    }

    @Test
    fun theShippedNameMatchesThePlatform() {
        assertEquals(
            if (isWindowsHost()) "adfree-native-host.exe" else "adfree-native-host",
            NativeMessagingHosts.hostBinaryName(),
        )
    }
}
