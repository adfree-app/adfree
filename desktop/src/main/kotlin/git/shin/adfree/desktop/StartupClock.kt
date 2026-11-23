package git.shin.adfree.desktop

/**
 * Where the seconds between a click and a window go.
 *
 * Opening AdFree feels slowest at the start, and "the app is slow" is not a
 * measurement: the process start, the elevation hand-off, the engine, the trust
 * stores and the first composition all happen in sequence in one thread, and
 * only one of them is worth its cost. Each is stamped here, against the moment
 * `main` was entered, so the fix goes where the time is.
 *
 * Temporary: it exists to answer a question, and it comes out with the answer.
 */
internal object StartupClock {
    private val startedAt = System.nanoTime()

    @Volatile
    var lastMark = 0L
        private set

    fun mark(what: String) {
        val ms = elapsedMs()
        lastMark = ms
        System.err.println("[startup] $what at ${ms}ms")
    }

    fun elapsedMs(): Long = (System.nanoTime() - startedAt) / 1_000_000
}
