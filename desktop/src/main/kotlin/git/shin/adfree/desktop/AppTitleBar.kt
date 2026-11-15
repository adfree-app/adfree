package git.shin.adfree.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.FrameWindowScope
import java.awt.Cursor
import java.awt.Frame
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * The app's own title bar, drawn inside the window.
 *
 * The OS frame was left to draw this, and on Windows it is a white band that
 * belongs to no theme: the system paints the caption in the light palette unless
 * an app opts into the dark one, and the result over a dark interface reads as a
 * different program. A Compose window can drop the frame entirely
 * (`undecorated = true`), and then the title bar is a row in the app's own
 * surface, in the app's own type — the same bar on every platform, and both
 * palettes.
 *
 * Everything here takes its colour from `MaterialTheme`, never from a constant:
 * the app already has a dark and a light palette and a bar with a fixed grey
 * would be wrong in one of them.
 *
 * What the frame used to do for free has to be done here: dragging, the double
 * click that maximises, and the three buttons. Resizing is [ResizeHandles],
 * because an undecorated window has no border to grab.
 */
@Composable
internal fun FrameWindowScope.AppTitleBar(
    title: String,
    onClose: () -> Unit,
    subtitle: String? = null,
) {
    val window = this.window
    // The frame's state, not a second copy of it: a window maximised by the
    // window manager, or restored by it, has to read the same here.
    var maximized by remember { mutableStateOf(window.extendedState and Frame.MAXIMIZED_BOTH != 0) }

    fun toggleMaximize() {
        maximized = window.extendedState and Frame.MAXIMIZED_BOTH == 0
        window.extendedState = if (maximized) Frame.MAXIMIZED_BOTH else Frame.NORMAL
    }

    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(titleBarHeight)
                .pointerInput(maximized) {
                    // The drag is measured from where it started, not from the
                    // step before: a window moved by the difference of two
                    // rounded positions walks a pixel or two off the cursor on
                    // every event and the pointer drifts out of the bar it is
                    // dragging.
                    var origin: Point? = null
                    var travelled = Offset.Zero
                    detectDragGestures(
                        onDragStart = {
                            origin = window.location
                            travelled = Offset.Zero
                        },
                        onDragEnd = { origin = null },
                        onDragCancel = { origin = null },
                    ) { change, drag ->
                        change.consume()
                        // A maximised window dragged by its caption is a restore
                        // first; imitating half of that is worse than not
                        // offering it, so the drag does nothing while maximised.
                        if (maximized) return@detectDragGestures
                        travelled += drag
                        window.location = movedTo(origin ?: window.location, travelled)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { toggleMaximize() })
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(14.dp))
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Spacer(Modifier.width(10.dp))
                Text(text = subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            CaptionButton(
                icon = { tint ->
                    Icon(Icons.Filled.Remove, contentDescription = "Minimise", tint = tint, modifier = Modifier.size(iconSize))
                },
                // Minimising to the taskbar is what the frame's button did;
                // hiding to the tray is the app's own [onClose] answer.
                onClick = { window.extendedState = Frame.ICONIFIED },
            )
            CaptionButton(
                icon = { tint ->
                    Icon(
                        if (maximized) Icons.Filled.FilterNone else Icons.Filled.CropSquare,
                        contentDescription = if (maximized) "Restore" else "Maximise",
                        tint = tint,
                        modifier = Modifier.size(iconSize),
                    )
                },
                onClick = { toggleMaximize() },
            )
            CaptionButton(
                icon = { tint ->
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = tint, modifier = Modifier.size(iconSize))
                },
                onClick = onClose,
                hoverBackground = MaterialTheme.colorScheme.error,
                hoverTint = MaterialTheme.colorScheme.onError,
            )
        }
        // A hairline instead of a shadow: the bar has to end somewhere, and a
        // surface against a surface with no edge between them is one flat area.
        HorizontalDivider(
            Modifier.align(Alignment.BottomCenter),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/** One caption button: the hover and press fills are the only feedback a caption offers. */
@Composable
private fun CaptionButton(
    icon: @Composable (Color) -> Unit,
    onClick: () -> Unit,
    hoverBackground: Color? = null,
    hoverTint: Color? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val fill = when {
        hovered && hoverBackground != null -> hoverBackground
        hovered || pressed -> MaterialTheme.colorScheme.surfaceVariant
        else -> Color.Transparent
    }
    val tint = when {
        hovered && hoverTint != null -> hoverTint
        hovered || pressed -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        Modifier
            .fillMaxHeight()
            .width(captionButtonWidth)
            .background(fill)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon(tint)
    }
}

/**
 * The eight grips an undecorated window needs to be resizable.
 *
 * Laid over the whole window rather than around each edge, so the corners can
 * take a diagonal drag: an edge that only resizes one axis under a diagonal
 * cursor feels broken, and a corner is two edges in one place.
 *
 * Every grip measures from the bounds it started with. Adding each event's delta
 * to the *current* bounds instead would re-read a value the window manager has
 * not applied yet — the rectangle would lag the pointer during a fast drag and
 * then jump when the queued change landed.
 */
@Composable
internal fun FrameWindowScope.ResizeHandles() {
    val window = this.window
    Box(Modifier.fillMaxSize()) {
        for (edge in ResizeEdge.entries) {
            Box(
                Modifier
                    .align(edge.alignment())
                    .then(edge.sizeModifier())
                    .pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(edge.cursor())))
                    .pointerInput(edge) {
                        var origin: Bounds? = null
                        var travelled = Offset.Zero
                        detectDragGestures(
                            onDragStart = {
                                val bounds = window.bounds
                                origin = Bounds(bounds.x, bounds.y, bounds.width, bounds.height)
                                travelled = Offset.Zero
                            },
                            onDragEnd = { origin = null },
                            onDragCancel = { origin = null },
                        ) { change, drag ->
                            change.consume()
                            val from = origin ?: return@detectDragGestures
                            travelled += drag
                            val resized = resized(
                                bounds = from,
                                edge = edge,
                                dx = travelled.x.roundToInt(),
                                dy = travelled.y.roundToInt(),
                                minWidth = window.minimumSize.width,
                                minHeight = window.minimumSize.height,
                            )
                            window.setBounds(Rectangle(resized.x, resized.y, resized.width, resized.height))
                        }
                    },
            )
        }
    }
}

/**
 * Where a dragged window has got to: the position it started at plus the whole
 * distance travelled, as one step.
 *
 * Adding each event's delta to the window's *current* position looks equivalent
 * and is not: the position is an integer while a pointer is not, so every event
 * contributes its own rounding error and a slow drag walks the window away from
 * the cursor that is holding it.
 */
internal fun movedTo(origin: Point, travelled: Offset): Point =
    Point(origin.x + travelled.x.roundToInt(), origin.y + travelled.y.roundToInt())

/** A window rectangle, as arithmetic instead of an AWT object. */
internal data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

/** Which side of the window a grip pulls. */
internal enum class ResizeEdge {
    NORTH, SOUTH, EAST, WEST, NORTH_EAST, NORTH_WEST, SOUTH_EAST, SOUTH_WEST;

    fun cursor(): Int = when (this) {
        NORTH -> Cursor.N_RESIZE_CURSOR
        SOUTH -> Cursor.S_RESIZE_CURSOR
        EAST -> Cursor.E_RESIZE_CURSOR
        WEST -> Cursor.W_RESIZE_CURSOR
        NORTH_EAST -> Cursor.NE_RESIZE_CURSOR
        NORTH_WEST -> Cursor.NW_RESIZE_CURSOR
        SOUTH_EAST -> Cursor.SE_RESIZE_CURSOR
        SOUTH_WEST -> Cursor.SW_RESIZE_CURSOR
    }

    fun alignment(): Alignment = when (this) {
        NORTH -> Alignment.TopCenter
        SOUTH -> Alignment.BottomCenter
        EAST -> Alignment.CenterEnd
        WEST -> Alignment.CenterStart
        NORTH_EAST -> Alignment.TopEnd
        NORTH_WEST -> Alignment.TopStart
        SOUTH_EAST -> Alignment.BottomEnd
        SOUTH_WEST -> Alignment.BottomStart
    }

    /** Corners are as thick as they are wide; an edge is thin along the axis it does not move. */
    fun sizeModifier(): Modifier = when (this) {
        NORTH, SOUTH -> Modifier.fillMaxWidth().height(gripThickness)
        EAST, WEST -> Modifier.fillMaxHeight().width(gripThickness)
        else -> Modifier.width(cornerGrip).height(cornerGrip)
    }
}

/**
 * The rectangle a drag produces: pulled edges move, the opposite edge stays put.
 *
 * `dx` and `dy` are the distance from where the drag started, not from the
 * previous event — see [ResizeHandles] for why that matters.
 *
 * The minimum is clamped by shortening the side being dragged rather than by
 * ignoring the drag, so a window already at its minimum keeps its edge under the
 * cursor instead of jumping away from it.
 */
internal fun resized(bounds: Bounds, edge: ResizeEdge, dx: Int, dy: Int, minWidth: Int, minHeight: Int): Bounds {
    var x = bounds.x
    var y = bounds.y
    var width = bounds.width
    var height = bounds.height
    val pullsWest = edge == ResizeEdge.WEST || edge == ResizeEdge.NORTH_WEST || edge == ResizeEdge.SOUTH_WEST
    val pullsEast = edge == ResizeEdge.EAST || edge == ResizeEdge.NORTH_EAST || edge == ResizeEdge.SOUTH_EAST
    val pullsNorth = edge == ResizeEdge.NORTH || edge == ResizeEdge.NORTH_EAST || edge == ResizeEdge.NORTH_WEST
    val pullsSouth = edge == ResizeEdge.SOUTH || edge == ResizeEdge.SOUTH_EAST || edge == ResizeEdge.SOUTH_WEST
    if (pullsWest) {
        val moved = dx.coerceAtMost(width - minWidth)
        x += moved
        width -= moved
    }
    if (pullsEast) {
        width = (width + dx).coerceAtLeast(minWidth)
    }
    if (pullsNorth) {
        val moved = dy.coerceAtMost(height - minHeight)
        y += moved
        height -= moved
    }
    if (pullsSouth) {
        height = (height + dy).coerceAtLeast(minHeight)
    }
    return Bounds(x, y, width, height)
}

/** The bar's height: tall enough for a caption, short enough not to be a toolbar. */
internal val titleBarHeight = 38.dp

private val captionButtonWidth = 46.dp
private val iconSize = 15.dp
private val gripThickness = 5.dp
private val cornerGrip = 12.dp
