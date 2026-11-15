package git.shin.adfree.desktop

/**
 * Which status field this session gets, and why.
 *
 * Two fields exist and they are not interchangeable: StatusNotifierItem is the
 * D-Bus protocol a Wayland shell answers (AWT's XEmbed icon reaches KDE's
 * `xembedsniproxy`, which paints it and returns no clicks), while AWT's
 * `SystemTray` is the platform field — the one Windows and macOS actually have,
 * and the only one an X11 session without a StatusNotifier host has.
 *
 * The order is therefore a property of the session, not a preference: ask for
 * the bus first where a bus exists, and fall back to the platform tray
 * everywhere else.
 */
internal enum class TrayBackend {
    /** The D-Bus icon: icon, clicks and the menu drawn by us. */
    StatusNotifier,

    /** The platform's own field, with the menu the platform draws. */
    Awt,

    /** Neither: no icon, and closing the window must quit rather than hide. */
    None,
}

/**
 * Picks the field from what the session answered.
 *
 * `sni` is whether a StatusNotifierItem was registered; `awtSupported` is what
 * `SystemTray.isSupported()` said. A registered item wins, because on Wayland
 * it is the only one that receives clicks.
 */
internal fun trayBackend(sni: Boolean, awtSupported: Boolean): TrayBackend = when {
    sni -> TrayBackend.StatusNotifier
    awtSupported -> TrayBackend.Awt
    else -> TrayBackend.None
}

/**
 * Whether asking the session bus for a StatusNotifierItem is worth the attempt.
 *
 * Windows and macOS have no session bus, and dbus-java does not answer "no" so
 * much as look for one: on a machine without D-Bus that is a wasted attempt at
 * best and a stalled start at worst. Their tray is the platform's, so the
 * question never comes up there.
 */
internal fun askForStatusNotifier(osName: String): Boolean {
    val os = osName.lowercase()
    return !os.startsWith("windows") && !os.startsWith("mac") && !os.startsWith("darwin")
}
