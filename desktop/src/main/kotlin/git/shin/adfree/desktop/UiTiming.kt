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
    val sampler =
        Thread({
            while (true) {
                val posted = System.nanoTime()
                val answer = java.util.concurrent.CountDownLatch(1)
                val late = longArrayOf(0)
                try {
                    SwingUtilities.invokeLater {
                        late[0] = (System.nanoTime() - posted) / 1_000_000
                        answer.countDown()
                    }
                    // A stall is sampled while it is happening: the stack of
                    // the thread that has not answered yet is the only place
                    // the work is, and asking afterwards answers "it is free".
                    var dumped = 0
                    while (!answer.await(500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        val busyFor = (System.nanoTime() - posted) / 1_000_000
                        if (busyFor >= EVENT_THREAD_LATE_MS && dumped < 3) {
                            dumped++
                            System.err.println(
                                "[ui] still busy at ${busyFor}ms: ${whereItWas(22)}",
                            )
                        }
                        if (busyFor > 30_000) {
                            System.err.println("[ui] the event thread did not answer for 30s")
                            break
                        }
                    }
                } catch (e: Throwable) {
                    System.err.println("[ui] event thread watch: ${e.message}")
                    return@Thread
                }
                if (late[0] >= EVENT_THREAD_LATE_MS) {
                    val until = StartupClock.elapsedMs()
                    val from = until - late[0]
                    System.err.println(
                        "[ui] the event thread was busy for ${late[0]}ms " +
                            "(from ${from}ms to ${until}ms): ${whereItWas()}",
                    )
                }
                Thread.sleep(EVENT_THREAD_SAMPLE_MS)
            }
        }, "adfree-event-thread-watch")
    sampler.isDaemon = true
    sampler.priority = Thread.MIN_PRIORITY + 1
    sampler.start()
}

private val started =
    java.util.concurrent.atomic
        .AtomicBoolean(false)

/** The event thread's own stack, which is where the wait is happening. */
private fun whereItWas(depth: Int = 6): String {
    val stack =
        Thread
            .getAllStackTraces()
            .entries
            .firstOrNull { it.key.name.startsWith("AWT-EventQueue") }
            ?.value
            ?: return "the event thread has no stack to show"
    return stack.take(depth).joinToString(" <- ") { frame ->
        val where = frame.className.substringAfterLast('.') + "." + frame.methodName
        if (frame.lineNumber >= 0) "$where:${frame.lineNumber}" else where
    }
}
