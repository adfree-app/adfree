package git.shin.adfree.desktop

import javax.swing.SwingUtilities

/**
 * How long the window's event thread makes a click wait.
 *
 * A window that feels like a turtle while the CPU sits idle is a thread that is
 * *waiting*, not working, and the difference cannot be seen from outside: every
 * probe a machine can run from the outside answers "not hung" because the
 * thread does pump — between the waits. So the thread is asked directly, from a
 * thread of its own, how long a no-op takes to run: that number is what a click
 * waits behind, and the stack of the thread at the moment it was late says what
 * it was waiting in.
 *
 * The cost is one `invokeLater` every [EVENT_THREAD_SAMPLE_MS], which is a few
 * microseconds.
 */
internal const val EVENT_THREAD_SAMPLE_MS = 100L

/** What a click is made to wait behind before it is worth saying out loud. */
internal const val EVENT_THREAD_LATE_MS = 300L

/**
 * Watches the event thread from the outside, and says so when it is late.
 *
 * Idempotent: the second call does nothing, so a window that opens twice does
 * not start two watchers.
 */
internal fun startEventThreadWatch() {
    if (!started.compareAndSet(false, true)) return
    val sampler = Thread({
        while (true) {
            val posted = System.nanoTime()
            val answer = java.util.concurrent.CountDownLatch(1)
            val late = longArrayOf(0)
            try {
                SwingUtilities.invokeLater {
                    late[0] = (System.nanoTime() - posted) / 1_000_000
                    answer.countDown()
                }
                // A thread that never answers is the case this whole file
                // exists to notice; waiting forever would notice nothing.
                if (!answer.await(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    System.err.println("[ui] the event thread did not answer for 30s")
                }
            } catch (e: Throwable) {
                System.err.println("[ui] event thread watch: ${e.message}")
                return@Thread
            }
            if (late[0] >= EVENT_THREAD_LATE_MS) {
                System.err.println(
                    "[ui] the event thread was busy for ${late[0]}ms: ${whereItWas()}",
                )
            }
            Thread.sleep(EVENT_THREAD_SAMPLE_MS)
        }
    }, "adfree-event-thread-watch")
    sampler.isDaemon = true
    sampler.priority = Thread.MIN_PRIORITY + 1
    sampler.start()
}

private val started = java.util.concurrent.atomic.AtomicBoolean(false)

/** The event thread's own stack, which is where the wait is happening. */
private fun whereItWas(): String {
    val stack = Thread.getAllStackTraces().entries
        .firstOrNull { it.key.name.startsWith("AWT-EventQueue") }
        ?.value
        ?: return "the event thread has no stack to show"
    return stack.take(6).joinToString(" <- ") { frame ->
        val where = frame.className.substringAfterLast('.') + "." + frame.methodName
        if (frame.lineNumber >= 0) "$where:${frame.lineNumber}" else where
    }
}

