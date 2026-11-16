package git.shin.adfree.desktop

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The double click the caption reads without taking the press.
 *
 * Compose's own tap detector consumes the press, and the window drag is watching
 * that same press — with both on the bar the window stopped moving altogether.
 * So the bar watches releases instead, and what decides a double click is the
 * pair of them: close in time *and* close in place. Without the place, ending a
 * drag and pressing the bar again a moment later would maximise the window.
 */
class TitleBarDoubleClickTest {

    @Test
    fun twoQuickReleasesInTheSamePlaceAreADoubleClick() {
        val taps = DoubleTapObserver()
        assertFalse("the first click is just a click", taps.onRelease(uptimeMillis = 1_000, where = Offset(100f, 18f)))
        assertTrue("the second one completes it", taps.onRelease(uptimeMillis = 1_200, where = Offset(101f, 19f)))
    }

    @Test
    fun aSlowSecondClickIsNotOne() {
        val taps = DoubleTapObserver()
        taps.onRelease(uptimeMillis = 1_000, where = Offset(100f, 18f))
        assertFalse(taps.onRelease(uptimeMillis = 2_000, where = Offset(100f, 18f)))
    }

    /**
     * A drag ends with a release too, and a person who drags the window and then
     * clicks the bar has not asked for a maximise: the two are close in time and
     * nowhere near each other.
     */
    @Test
    fun theReleaseThatEndsADragIsNotHalfOfADoubleClick() {
        val taps = DoubleTapObserver()
        // A drag of the bar, released where the hand let go.
        taps.onRelease(uptimeMillis = 1_000, where = Offset(300f, 18f))
        assertFalse(
            "a click elsewhere a moment later is not the second half of it",
            taps.onRelease(uptimeMillis = 1_100, where = Offset(120f, 18f)),
        )
        // Two clicks at the new place still are a double click: the drag is not
        // a reason to stop answering the gesture the bar has always answered.
        assertTrue(taps.onRelease(uptimeMillis = 1_200, where = Offset(120f, 18f)))
    }

    @Test
    fun threeClicksAreOneDoubleClickNotOneAndAHalf() {
        val taps = DoubleTapObserver()
        taps.onRelease(uptimeMillis = 1_000, where = Offset(100f, 18f))
        assertTrue(taps.onRelease(uptimeMillis = 1_100, where = Offset(100f, 18f)))
        assertFalse("the pair has been used up", taps.onRelease(uptimeMillis = 1_200, where = Offset(100f, 18f)))
    }
}
