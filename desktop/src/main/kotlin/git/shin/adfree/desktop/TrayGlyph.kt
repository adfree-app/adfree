package git.shin.adfree.desktop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/** The mark's green, and the amber that means filtering is off. */
internal val GlyphGreen = Color(0xFF00D06C)
internal val GlyphAmber = Color(0xFFFFB020)

/**
 * The pair a tray glyph is shaded with. A flat fill is a sticker; a shade top to
 * bottom is what makes sixteen pixels look drawn rather than pasted, and it is
 * cheap enough not to matter at any size.
 */
private val GlyphGreenTop = Color(0xFF2FF094)
private val GlyphGreenBottom = Color(0xFF00A858)
private val GlyphAmberTop = Color(0xFFFFC451)
private val GlyphAmberBottom = Color(0xFFE08A00)

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
    val (top, bottom) = if (paused) {
        GlyphAmberTop to GlyphAmberBottom
    } else {
        GlyphGreenTop to GlyphGreenBottom
    }
    val bitmap = ImageBitmap(sidePx, sidePx)
    CanvasDrawScope().draw(
        Density(1f),
        LayoutDirection.Ltr,
        Canvas(bitmap),
        Size(side, side),
    ) {
        // A shield with a rounded head, so the mark has the same soft shape the
        // app's own artwork does — a hard-cornered triangle reads as a warning
        // sign at sixteen pixels, which is not what this says.
        val shield = Path().apply {
            moveTo(side * 0.17f, side * 0.05f)
            quadraticTo(side * 0.11f, side * 0.05f, side * 0.11f, side * 0.13f)
            lineTo(side * 0.11f, side * 0.42f)
            quadraticTo(side * 0.11f, side * 0.74f, side * 0.5f, side * 0.97f)
            quadraticTo(side * 0.89f, side * 0.74f, side * 0.89f, side * 0.42f)
            lineTo(side * 0.89f, side * 0.13f)
            quadraticTo(side * 0.89f, side * 0.05f, side * 0.83f, side * 0.05f)
            close()
        }
        drawPath(shield, Brush.verticalGradient(listOf(top, bottom)))
        // An edge, not a border: on a light taskbar a green shape with no
        // outline has no boundary against its own highlight, and an outline
        // drawn any heavier than a hair is what made sixteen pixels muddy.
        drawPath(shield, BoltDark.copy(alpha = 0.4f), style = Stroke(width = side * 0.045f))
        val bolt = Path().apply {
            // Nudged a shade right of the shield's centre line: a bolt leans
            // left by its own shape, and centred by arithmetic it reads as
            // sitting off to one side.
            moveTo(side * 0.615f, side * 0.25f)
            lineTo(side * 0.35f, side * 0.56f)
            lineTo(side * 0.485f, side * 0.56f)
            lineTo(side * 0.42f, side * 0.78f)
            lineTo(side * 0.685f, side * 0.46f)
            lineTo(side * 0.55f, side * 0.46f)
            close()
        }
        drawPath(bolt, BoltDark)
        // Nothing else: the bitmap starts transparent and stays that way
        // everywhere the mark does not reach, which is what a taskbar expects of
        // an icon it draws over its own colour.
    }
    return bitmap
}
