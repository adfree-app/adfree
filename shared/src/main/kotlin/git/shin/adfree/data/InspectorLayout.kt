package git.shin.adfree.data

import git.shin.adfree.platform.KeyValueStore

/** Which edge of the tracker window the detail pane is pinned to. */
enum class InspectorDock { BOTTOM, RIGHT, LEFT }

/**
 * Where the detail pane sat and how much room it took, the last time the window
 * was open.
 *
 * A panel that forgets is a panel that has to be dragged again on every open,
 * and the reader who prefers the pane on the right prefers it on the right
 * every time. The two numbers live in the platform's key-value store, next to
 * the rest of the app's small preferences; a store that is not there — a test,
 * a headless build — leaves the defaults in place.
 */
class InspectorLayout(private val store: KeyValueStore) {
    fun dock(): InspectorDock =
        when (store.getString(KEY_DOCK)) {
            "RIGHT" -> InspectorDock.RIGHT
            "LEFT" -> InspectorDock.LEFT
            else -> InspectorDock.BOTTOM
        }

    /** The pane's share of the axis, or `null` when nothing was ever saved. */
    fun ratio(): Float? {
        val saved = store.getString(KEY_RATIO)?.toFloatOrNull() ?: return null
        return saved.takeIf { it > 0f && it < 1f }
    }

    fun remember(
        dock: InspectorDock,
        ratio: Float,
    ) {
        store.putString(KEY_DOCK, dock.name)
        store.putString(KEY_RATIO, ratio.coerceIn(MIN_RATIO, MAX_RATIO).toString())
    }

    companion object {
        const val DEFAULT_RATIO = 0.42f
        const val MIN_RATIO = 0.12f
        const val MAX_RATIO = 0.78f
        private const val KEY_DOCK = "inspector.dock"
        private const val KEY_RATIO = "inspector.ratio"
    }
}
