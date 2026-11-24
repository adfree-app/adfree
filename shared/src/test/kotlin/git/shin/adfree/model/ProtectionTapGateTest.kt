package git.shin.adfree.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * One transition at a time.
 *
 * The switch is read against the tunnel, and the tunnel answers with the state
 * it has *now* — which, right after a tap, is still the state that tap is
 * ending. Admitting the second tap in that window is what made "off, then on"
 * need three taps.
 */
class ProtectionTapGateTest {
    @Test
    fun `the first tap is admitted and the one inside it is not`() {
        val gate = ProtectionTapGate()

        assertTrue(gate.admit())
        assertTrue(gate.busy)
        assertFalse("a tap mid-transition is not a tap against the old state", gate.admit())
    }

    @Test
    fun `a settled gate admits the next tap`() {
        val gate = ProtectionTapGate()

        assertTrue(gate.admit())
        gate.settled()

        assertFalse(gate.busy)
        assertTrue(gate.admit())
    }

    /**
     * Settling twice is what a timeout and a finished transition do together:
     * the second one must not give away a right that a *newer* tap took.
     */
    @Test
    fun `settling does not disturb a tap that came afterwards`() {
        val gate = ProtectionTapGate()

        assertTrue(gate.admit())
        gate.settled()
        assertTrue(gate.admit())
        gate.settled()

        assertFalse(gate.busy)
        assertTrue(gate.admit())
    }

    /** Taps arrive on the UI thread and the settle lands on a coroutine. */
    @Test
    fun `only one of many racing taps is admitted`() {
        val gate = ProtectionTapGate()
        val admitted = AtomicInteger()
        val threads = ArrayList<Thread>()
        repeat(8) {
            threads += Thread { if (gate.admit()) admitted.incrementAndGet() }
        }
        threads.forEach(Thread::start)
        threads.forEach { thread -> thread.join(5_000) }

        assertTrue("exactly one tap starts a transition, got ${admitted.get()}", admitted.get() == 1)
    }
}
