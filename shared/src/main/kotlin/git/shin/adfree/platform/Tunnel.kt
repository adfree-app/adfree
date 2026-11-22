package git.shin.adfree.platform

import kotlinx.coroutines.flow.StateFlow

/**
 * The VPN tunnel the protection switch drives. Android backs it with
 * VpnService; the desktop host runs the engine in-process and answers that a
 * tunnel is always up once the core is.
 */
interface TunnelControl {

    /** True while the OS reports the tunnel established. */
    val isVpnActive: StateFlow<Boolean>

    /**
     * Whether protection that was on when the app last ran comes back by itself.
     *
     * True where the OS owns the tunnel: Android's VpnService is a system
     * service, so a start that failed once is not the same as a start that
     * cannot work. False on a desktop, where protection is a privileged kernel
     * redirect that a freshly launched, perhaps unelevated, process may be
     * unable to install — a restored switch there is the window claiming a
     * filter that is not running, which is the state that reads as "the machine
     * lost its internet" rather than as "protection is off".
     */
    val restoresProtectionOnLaunch: Boolean get() = true

    /** Tunnel up per the OS *and* the core: a dispatched start can still fail. */
    fun isTunnelUp(): Boolean

    /** True when the system VPN consent dialog has to be raised first. */
    fun needsConsent(): Boolean

    fun startVpnService()

    fun stopVpnService()

    fun restartVpnService()

    /** The consent dialog answered; unblocks the pending start. */
    fun onPermissionResult(granted: Boolean)

    /** Re-posts or clears the ongoing-VPN status notification. */
    fun refreshStatusNotification()
}
