package git.shin.adfree.desktop

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path

/** The mark's green, and the amber that means filtering is off. */
internal val GlyphGreen = Color(0xFF00D06C)
internal val GlyphAmber = Color(0xFFFFB020)

/** The bolt, in the shade the app's own icon paints it. */
private val BoltDark = Color(0xFF07110B)

/**
 * The tray icon Windows gets: the mark on a transparent canvas, filling it.
 *
 * The tray is the one place an icon is not a picture but a glyph. Windows draws
 * it at 16 pixels, so the app's own artwork — a rounded dark tile with generous
 * padding and a soft glow — arrives as a smudged square with a dot in the
 * middle: the tile's own edges and the padding around them are most of what is
 * left after the downscale. The icon here is the shield and the bolt and nothing
 * else, drawn at the canvas's own size so a pixel of the shape is a pixel of the
 * tray, and the corners it does not cover stay transparent so the taskbar's own
 * colour shows through.
 *
 * The state is the colour, as it is on Linux: green while filtering, amber when
 * it is paused.
 */
internal fun windowsTrayGlyph(paused: Boolean, sidePx: Int = 32): ImageBitmap {
    val side = sidePx.coerceAtLeast(8).toFloat()
    val color = if (paused) GlyphAmber else GlyphGreen
    return ImageBitmap(sidePx, sidePx).also { bitmap ->
        Canvas(bitmap).apply {
            val shield = Path().apply {
                moveTo(side * 0.105f, side * 0.02f)
                lineTo(side * 0.895f, side * 0.02f)
                lineTo(side * 0.895f, side * 0.44f)
                quadraticTo(side * 0.895f, side * 0.78f, side * 0.5f, side * 0.985f)
                quadraticTo(side * 0.105f, side * 0.78f, side * 0.105f, side * 0.44f)
                close()
            }
            drawPath(shield, Paint().apply { this.color = color })
            val bolt = Path().apply {
                moveTo(side * 0.59f, side * 0.25f)
                lineTo(side * 0.34f, side * 0.56f)
                lineTo(side * 0.5f, side * 0.56f)
                lineTo(side * 0.41f, side * 0.78f)
                lineTo(side * 0.69f, side * 0.47f)
                lineTo(side * 0.53f, side * 0.47f)
                close()
            }
            drawPath(bolt, Paint().apply { this.color = BoltDark })
            // Nothing else: the bitmap starts transparent and stays that way
            // everywhere the mark does not reach, which is what a taskbar
            // expects of an icon it draws over its own colour.
        }
    }
}
