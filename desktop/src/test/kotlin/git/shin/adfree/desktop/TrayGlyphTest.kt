package git.shin.adfree.desktop

import androidx.compose.ui.graphics.toPixelMap
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The icon Windows draws in its tray.
 *
 * Windows renders a tray icon at 16 pixels over the taskbar's own colour, so
 * what arrives has to be the mark and nothing else: the app's artwork is a
 * rounded dark tile with padding and a glow, and at 16 pixels those are most of
 * what is left of it — the "squashed" tray nobody can read. So the corners are
 * transparent, the mark reaches the edges of the canvas, and the colour is the
 * state.
 */
class TrayGlyphTest {

    private val glyph = windowsTrayGlyph(paused = false)
    private val pixels = glyph.toPixelMap()

    @Test
    fun theCornersAreTransparent() {
        val last = glyph.width - 1
        for ((x, y) in listOf(0 to 0, last to 0, 0 to last, last to last)) {
            assertEquals(
                "corner $x,$y carries a background the taskbar did not ask for",
                0f,
                pixels[x, y].alpha,
                0.01f,
            )
        }
    }

    /**
     * No padding: the mark's first and last rows of pixels are the canvas's.
     * A glyph inset by a couple of pixels is a 16-pixel tray with 12 usable
     * ones, which is the smudge this exists to avoid.
     */
    @Test
    fun theMarkReachesTheEdgesOfTheCanvas() {
        val rows = (0 until glyph.height).filter { y ->
            (0 until glyph.width).any { x -> pixels[x, y].alpha > 0.5f }
        }
        assertTrue("the mark must not start after the first row: $rows", rows.first() <= 1)
        assertTrue("the mark must reach the bottom rows: $rows", rows.last() >= glyph.height - 3)
    }

    @Test
    fun theMarkIsGreenWhileFilteringAndAmberWhenPaused() {
        val centre = glyph.width / 2
        // The shield's shoulder, above the bolt: a pixel that is the mark itself.
        val green = pixels[centre, (glyph.height * 0.2f).toInt()]
        assertTrue("filtering is green: $green", green.green > green.red && green.green > 0.5f)

        val paused = windowsTrayGlyph(paused = true).toPixelMap()
        val amber = paused[centre, (glyph.height * 0.2f).toInt()]
        assertTrue("paused is amber: $amber", amber.red > amber.blue && abs(amber.red - amber.green) < 0.35f)
    }

    @Test
    fun theIconIsNotAPlate() {
        // At least a third of the canvas is empty: an icon whose every pixel is
        // painted is a tile, and a tile at 16 pixels is a coloured square.
        var clear = 0
        for (y in 0 until glyph.height) {
            for (x in 0 until glyph.width) {
                if (pixels[x, y].alpha < 0.5f) clear++
            }
        }
        assertTrue("too little of the canvas is transparent: $clear", clear * 3 >= glyph.width * glyph.height)
    }
}
