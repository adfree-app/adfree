package git.shin.adfree.desktop

import androidx.compose.ui.graphics.toAwtImage
import java.io.File
import javax.imageio.ImageIO
import git.shin.adfree.l10n.L10n
import git.shin.adfree.l10n.Strings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tray is the app's state in a place the user can see while the window is
 * hidden, and on Windows it is the *only* such place: the platform tray shows a
 * picture and a tooltip and nothing else.
 *
 * So the two pictures are written out here and compared — not pixel for pixel,
 * which would fail the first time the app icon is redrawn, but as "the paused
 * one is different, and it is different in the corner where a status mark
 * belongs". The files are left in `build/tmp` so a human can look at them.
 */
class TrayIconTest {

    private val out = File("build/tmp/tray-icon").apply { mkdirs() }

    @Test
    fun theFilteringIconIsTheAppIcon() {
        val file = File(out, "tray-on.png")
        ImageIO.write(pausedTrayGlyph(false).toAwtImage(), "png", file)
        assertTrue("the icon is written for eyeballing", file.isFile)
        assertTrue("and it is not empty", file.length() > 0)
    }

    @Test
    fun thePausedIconCarriesAMarkInTheCorner() {
        val on = pausedTrayGlyph(false).toAwtImage()
        val off = pausedTrayGlyph(true).toAwtImage()
        File(out, "tray-off.png").let { ImageIO.write(off, "png", it) }

        val w = minOf(on.width, off.width)
        val h = minOf(on.height, off.height)
        var differing = 0
        var cornerDiffering = 0
        for (x in 0 until w) {
            for (y in 0 until h) {
                if (on.getRGB(x, y) != off.getRGB(x, y)) {
                    differing += 1
                    // The mark is expected in the bottom-right quarter; a
                    // recolour of the whole icon would light up everywhere.
                    if (x > w / 2 && y > h / 2) cornerDiffering += 1
                }
            }
        }
        assertTrue("the paused icon is a different picture", differing > 0)
        assertTrue("the mark is a mark, not a recolour", differing < w * h / 2)
        assertTrue("and it sits in the corner", cornerDiffering > differing * 8 / 10)
    }

    /** Windows shows this on hover and nowhere else. */
    @Test
    fun theTooltipSaysWhatIsHappening() {
        assertTrue(trayTooltip(false).startsWith("AdFree"))
        assertTrue(trayTooltip(true).startsWith("AdFree"))
        assertNotEquals(trayTooltip(false), trayTooltip(true))
    }

    /**
     * The hover text is a state, not a command cut off at its first space.
     *
     * It was built from the pause *menu item* and kept the first word, so a tray
     * that was busy filtering said "AdFree — Tạm" — a fragment that reads as
     * "temporary" and never says whether anything is being protected.
     */
    @Test
    fun theTooltipIsAWholePhraseAndNotAWordFragment() {
        val protected = trayTooltip(false)
        val paused = trayTooltip(true)
        assertEquals("AdFree — ${L10n.get(Strings.home_shield_protected)}", protected)
        assertEquals("AdFree — ${L10n.get(Strings.tile_state_paused)}", paused)
        // The bug's shape: a menu item cut at its first space. A state that is a
        // proper prefix of a command is that cut showing through.
        val commands = listOf(Strings.tray_pause_30s, Strings.tray_pause_1h, Strings.tray_resume)
            .map { L10n.get(it) }
        for (text in listOf(protected, paused)) {
            val state = text.removePrefix("AdFree — ")
            for (command in commands) {
                assertFalse(
                    "a cut-off command is not a state: $text",
                    command.startsWith(state.trim()) && command != state.trim(),
                )
            }
        }
    }
}
