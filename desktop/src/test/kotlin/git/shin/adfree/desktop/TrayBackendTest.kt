package git.shin.adfree.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tray decision, on sessions we cannot all run.
 *
 * The Windows build exists to prove that a Windows user gets an installable
 * app; the tray was the open question there, and the answer is a branch nobody
 * on Linux can click their way through. These cases pin the branch instead.
 */
class TrayBackendTest {
    @Test
    fun windows_gets_the_platform_tray() {
        // No session bus to ask, and AWT's field is the one Windows has.
        assertFalse(askForStatusNotifier("Windows 11"))
        assertEquals(TrayBackend.Awt, trayBackend(sni = false, awtSupported = true))
    }

    @Test
    fun a_dbus_icon_wins_where_one_was_registered() {
        // Wayland: the XEmbed icon paints and then eats every click.
        assertEquals(TrayBackend.StatusNotifier, trayBackend(sni = true, awtSupported = true))
    }

    @Test
    fun a_linux_session_without_a_bus_keeps_the_platform_tray() {
        // Plain X11 with a tray owner and no StatusNotifier host.
        assertTrue(askForStatusNotifier("Linux"))
        assertEquals(TrayBackend.Awt, trayBackend(sni = false, awtSupported = true))
    }

    @Test
    fun macos_has_no_session_bus_either() {
        assertFalse(askForStatusNotifier("Mac OS X"))
        assertFalse(askForStatusNotifier("Darwin"))
    }

    @Test
    fun with_neither_field_closing_the_window_must_quit() {
        // A headless or bare session: hiding the window would leave an app the
        // user cannot reach.
        assertEquals(TrayBackend.None, trayBackend(sni = false, awtSupported = false))
    }
}
