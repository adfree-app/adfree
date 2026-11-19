package git.shin.adfree.data

import git.shin.adfree.platform.InMemoryKeyValueStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pane the reader last positioned.
 *
 * The failure this guards is a window that opens where the defaults put it: a
 * pane dragged to the right, reopened on the bottom, is a pane that has to be
 * moved again every time — and the stored value is text, so it has to survive a
 * round trip through a string and a half-written file.
 */
class InspectorLayoutTest {
    private val store = InMemoryKeyValueStore()
    private val layout get() = InspectorLayout(store)

    @Test
    fun aStoreWithNothingInItReportsTheDefaults() {
        assertEquals(InspectorDock.BOTTOM, layout.dock())
        assertNull(layout.ratio())
    }

    @Test
    fun whatWasRememberedIsWhatComesBack() {
        layout.remember(InspectorDock.RIGHT, 0.61f)
        assertEquals(InspectorDock.RIGHT, layout.dock())
        assertEquals(0.61f, layout.ratio() ?: 0f, 0.001f)
    }

    @Test
    fun aRatioOutsideTheWindowIsBroughtBackIntoIt() {
        layout.remember(InspectorDock.LEFT, 4f)
        assertEquals(InspectorLayout.MAX_RATIO, layout.ratio() ?: 0f, 0.001f)
        layout.remember(InspectorDock.LEFT, 0f)
        assertEquals(InspectorLayout.MIN_RATIO, layout.ratio() ?: 0f, 0.001f)
    }

    @Test
    fun aStoredRatioThatCannotBeAPaneIsIgnored() {
        store.putString("inspector.ratio", "wide")
        assertNull(layout.ratio())
        store.putString("inspector.ratio", "1.4")
        assertNull(layout.ratio())
    }
}
