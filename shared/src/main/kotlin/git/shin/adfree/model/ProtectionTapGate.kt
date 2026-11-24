package git.shin.adfree.model

/**
 * What a second tap on the protection switch means while the first is still
 * landing.
 *
 * The switch follows the tunnel rather than a remembered flag, and the tunnel
 * is slow to change: stopping has to shut two listeners and join the threads
 * that were serving them, and starting may answer a privileged instance that
 * needs seconds to come up. A tap that arrives inside that window was read
 * against the state the tunnel still had — so the second half of "off, then
 * on" read as another "off", wrote the flag back down and told the tunnel to
 * stop again. The third tap was the first one that turned protection back on,
 * which a person reads as "protection cannot be turned back on at all"
 * (reported 23/11/2026).
 *
 * So one transition at a time: a tap while the last one is still settling is
 * not answered, because the state it would be read against is the state that
 * is on its way out.
 */
class ProtectionTapGate {
    /** Whether a transition is in flight: the window in which taps are ignored. */
    @Volatile
    var busy: Boolean = false
        private set

    /**
     * Takes the right to start a transition.
     *
     * False means one is already running and this tap changes nothing — the
     * screen already shows the answer to the tap before it.
     */
    fun admit(): Boolean {
        synchronized(this) {
            if (busy) return false
            busy = true
            return true
        }
    }

    /**
     * Gives the right back, once the tunnel has reached the state the switch
     * asked for or the wait for it ran out.
     *
     * Called on a path that cannot throw — a gate left busy is a switch that
     * answers nothing until the process restarts.
     */
    fun settled() {
        synchronized(this) {
            busy = false
        }
    }
}
