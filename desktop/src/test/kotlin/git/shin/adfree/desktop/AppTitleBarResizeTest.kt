package git.shin.adfree.desktop

import java.awt.Cursor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The arithmetic behind an undecorated window's grips.
 *
 * A frame does this for free, and doing it by hand has one rule that is easy to
 * get wrong: the edge opposite the one being pulled must not move. A window whose
 * left edge is dragged should shrink from the left — the right side staying where
 * it is — and clamping at the minimum has to shorten the side under the cursor
 * rather than let the window drift away from it.
 */
class AppTitleBarResizeTest {

    private val start = Bounds(x = 100, y = 100, width = 1000, height = 700)

    @Test
    fun theWestEdgeMovesWhileTheEastEdgeStays() {
        val moved = resized(start, ResizeEdge.WEST, dx = -40, dy = 0, minWidth = 700, minHeight = 500)
        assertEquals(60, moved.x)
        assertEquals("the far edge is untouched", 1100, moved.x + moved.width)
        assertEquals(1040, moved.width)
        assertEquals(100, moved.y)
    }

    @Test
    fun theNorthEdgeMovesWhileTheSouthEdgeStays() {
        val moved = resized(start, ResizeEdge.NORTH, dx = 0, dy = -30, minWidth = 700, minHeight = 500)
        assertEquals(70, moved.y)
        assertEquals("the far edge is untouched", 800, moved.y + moved.height)
        assertEquals(730, moved.height)
    }

    @Test
    fun draggingTheCornerMovesBothAxes() {
        val moved = resized(start, ResizeEdge.NORTH_WEST, dx = 50, dy = 25, minWidth = 700, minHeight = 500)
        assertEquals(150, moved.x)
        assertEquals(125, moved.y)
        assertEquals(950, moved.width)
        assertEquals(675, moved.height)
        assertEquals(1100, moved.x + moved.width)
        assertEquals(800, moved.y + moved.height)
    }

    /**
     * At the minimum the edge stops, it does not run away: a window that kept
     * growing its origin while refusing to shrink would slide out from under the
     * cursor.
     */
    @Test
    fun aWindowAtItsMinimumStopsInsteadOfSliding() {
        val small = Bounds(x = 100, y = 100, width = 700, height = 500)
        val moved = resized(small, ResizeEdge.NORTH_WEST, dx = 400, dy = 400, minWidth = 700, minHeight = 500)
        assertEquals(small, moved)
    }

    @Test
    fun anEastGripGrowsToTheRightAndKeepsItsOrigin() {
        val moved = resized(start, ResizeEdge.EAST, dx = 120, dy = 0, minWidth = 700, minHeight = 500)
        assertEquals(100, moved.x)
        assertEquals(1120, moved.width)
    }

    @Test
    fun aSouthGripAtTheMinimumKeepsTheHeightRatherThanInverting() {
        val moved = resized(start, ResizeEdge.SOUTH, dx = 0, dy = -5000, minWidth = 700, minHeight = 500)
        assertEquals(500, moved.height)
        assertEquals(100, moved.y)
    }

    /** The cursor is what tells a person the edge is grabbable, so it has to match. */
    @Test
    fun everyGripCarriesTheCursorOfItsEdge() {
        assertEquals(Cursor.N_RESIZE_CURSOR, ResizeEdge.NORTH.cursor())
        assertEquals(Cursor.S_RESIZE_CURSOR, ResizeEdge.SOUTH.cursor())
        assertEquals(Cursor.E_RESIZE_CURSOR, ResizeEdge.EAST.cursor())
        assertEquals(Cursor.W_RESIZE_CURSOR, ResizeEdge.WEST.cursor())
        assertEquals(Cursor.NE_RESIZE_CURSOR, ResizeEdge.NORTH_EAST.cursor())
        assertEquals(Cursor.NW_RESIZE_CURSOR, ResizeEdge.NORTH_WEST.cursor())
    }
}
