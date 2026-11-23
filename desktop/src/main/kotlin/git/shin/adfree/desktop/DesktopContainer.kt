package git.shin.adfree.desktop

import git.shin.adfree.core.ConfigOutcome
import git.shin.adfree.core.ConfigWarning
import git.shin.adfree.core.CoreEngine
import git.shin.adfree.core.coreSetDataRoot
import git.shin.adfree.data.AdGuardRepository
import git.shin.adfree.l10n.L10n
import git.shin.adfree.platform.LogSink
import git.shin.adfree.platform.NoopReminders
import git.shin.adfree.platform.AssistantBridge
import git.shin.adfree.platform.TunnelControl
import git.shin.adfree.security.ThreatAnalyzer
import git.shin.adfree.ui.AdGuardViewModel
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The desktop's hand-rolled container, mirroring the Android one object for
 * object: same repository, engine and ViewModel — only the seams differ.
 */
class DesktopContainer(
    /** Tests point this at a scratch dir; the app uses XDG data home. */
    val dataRoot: File = File(
        System.getenv("XDG_DATA_HOME")
            ?: File(System.getProperty("user.home"), ".local/share").absolutePath,
        "adfree",
    ),
) {

    val coreLibrary: File? = findCoreLibrary()

    /** False when no core library was found: the window says so instead of crashing. */
    val engineAvailable: Boolean = coreLibrary != null

    init {
        coreLibrary?.let {
            System.setProperty("uniffi.component.adfree.libraryOverride", it.absolutePath)
        }
    }

    val coreEngine: CoreEngine by lazy {
        CoreEngine(
            log = LogSink { System.err.println("[core] $it") },
            caExporter = DesktopCaExporter(dataRoot),
            // The core reads the distro's own trust store (see
            // `rust/src/trust.rs`): trusted means the PEM is in whichever
            // anchor directory this machine has and its rehash has run.
            // Called straight on the bindings because this runs while the
            // engine itself is being built.
            caTrustChecker = git.shin.adfree.platform.CaTrustChecker {
                runCatching { git.shin.adfree.core.coreRootTrustStatus().installed }
                    .getOrDefault(false)
            },
        ).also {
            dataRoot.mkdirs()
            runCatching { coreSetDataRoot(dataRoot.absolutePath) }
        }
    }

    /** The system's privilege answers: the mark, the rules, the service. */
    val privileges = DesktopPrivileges()

    /** "Start with the system": an XDG entry, written when the switch moves. */
    val autostart = DesktopAutostart()

    /** Set by the shell so the app can hand its window over to a privileged instance. */
    var onEngineTakeover: () -> Unit = {}

    /**
     * The lock this window holds over the machine's engine. The hand-over to
     * the privileged unit is a hand-over of this object, not of the window:
     * the unit runs a second `main` and cannot start filtering until the lock
     * is free.
     */
    var instanceLock: InstanceLock? = null

    /** The scope [start] gave the bridge, so a refused hand-over can reopen it. */
    private var bridgeScope: CoroutineScope? = null

    /**
     * Set by the shell so a request from outside — the browser extension's
     * "open the filtering log" — can show the window it is asking about.
     */
    var onShowWindow: () -> Unit = {}

    /**
     * Opens the filtering log window and says whether it did. The extension's
     * "open filtering log" asks for this window, not for the Activity tab of a
     * window that may be behind the browser.
     */
    var onShowFilteringLog: () -> Boolean = { false }

    /** The same door for the request inspector: open its window, or say no. */
    var onShowInspector: () -> Boolean = { false }

    val platformHost = DesktopPlatformHost(
        File(dataRoot, "adfree-ca.pem"),
        privileges = privileges,
        onEngineTakeover = { onEngineTakeover() },
        coreLibrary = coreLibrary,
        exportCaPem = { coreEngine.exportCaCertificate() },
        // Read at the ask, not captured: the ports are the user's to move, and
        // the probe has to look at the one the unit was told to take.
        privilegedPort = { repository.settings.value.localProxy.tcpPort },
        onRaiseWindow = { onShowWindow() },
        onOpenFilteringLog = { onShowFilteringLog() },
        onOpenInspector = { onShowInspector() },
        versionOf = { coreEngine.version },
    )
    val fileHost = DesktopFileHost()
    val inventory = DesktopAppInventory()
    private val reminders = NoopReminders()
    val store: git.shin.adfree.platform.KeyValueStore by lazy {
        DesktopKeyValueStore(File(dataRoot, "automation.properties"))
    }

    val repository: AdGuardRepository by lazy {
        AdGuardRepository(
            platform = platformHost,
            files = fileHost,
            inventory = inventory,
            reminders = reminders,
            store = store,
            engine = coreEngine,
        ) { applyConfig() }
    }

    val threatAnalyzer: ThreatAnalyzer by lazy { ThreatAnalyzer(repository, coreEngine) }

    val appUpdate: DesktopAppUpdate by lazy {
        DesktopAppUpdate(coreEngine.version, openUrl = platformHost::openUrl)
    }

    private val _configWarning = MutableStateFlow(ConfigWarning.NONE)
    val configWarning: StateFlow<ConfigWarning> = _configWarning.asStateFlow()

    /** Desktop protection has no OS tunnel to report, so the switch follows the persisted intent. */
    private val protectionWanted = MutableStateFlow(false)

    /**
     * The protection switch itself. Shared with the ViewModel and with the
     * browser bridge: AdGuard's extension asks the same question the shield
     * answers ("is the machine being filtered?"), and it has to hear the same
     * answer.
     */
    val tunnelControl: TunnelControl by lazy {
        DesktopTunnelControl(
            coreEngine,
            protectionWanted,
            privileges,
            // Read at every start rather than captured: the ports are the
            // ones the user last applied, so the listeners and the redirect
            // rules always name the same numbers.
            localProxy = { repository.settings.value.localProxy },
            // Filtering the machine's own traffic is what the shield turns on,
            // so the shield is what asks the system for the privilege — once,
            // on a machine that has not been set up yet.
            requestPrivileges = { platformHost.requestPrivilegedEngine() },
            report = { platformHost.toast(it) },
            releaseInstanceLock = { instanceLock?.release() },
            reacquireInstanceLock = { instanceLock?.acquire() ?: true },
            releaseAssistantBridge = { releaseAssistantBridge() },
            restoreAssistantBridge = { restoreAssistantBridge() },
        )
    }

    /**
     * Gives the browser extension's socket to the unit that is about to replace
     * this window, and takes it back when the unit did not take over after all.
     * The scope is the one [start] was given: a restored bridge lives exactly
     * as long as the window that owns it.
     */
    fun releaseAssistantBridge() {
        System.err.println("[desktop] handing the assistant bridge to the unit")
        assistantBridge.stop()
    }

    fun restoreAssistantBridge() {
        System.err.println("[desktop] the hand-over did not happen; reopening the assistant bridge")
        bridgeScope?.let { assistantBridge.start(it) }
    }

    val viewModel: AdGuardViewModel by lazy {
        AdGuardViewModel(
            repository = repository,
            tunnel = tunnelControl,
            threatAnalyzer = threatAnalyzer,
            coreEngine = coreEngine,
            platform = platformHost,
            log = LogSink { System.err.println("[ui] $it") },
            onConfigChanged = { applyConfig() },
            isPrivateDnsStrict = { false },
            configWarning = configWarning,
            appUpdate = appUpdate,
        )
    }

    /**
     * The socket AdGuard's browser extension reaches through
     * `adfree-native-host` (see [AssistantBridge]).
     */
    val assistantBridge: AssistantBridge by lazy {
        AssistantBridge(
            engine = coreEngine,
            repository = repository,
            viewModel = viewModel,
            tunnel = tunnelControl,
            appVersion = coreEngine.version,
            locale = { L10n.locale() },
            raiseWindow = { platformHost.raiseWindow() },
            openFilteringLog = { platformHost.openFilteringLog() },
            log = { System.err.println("[bridge] $it") },
            // The core publishes its endpoint here and the helper looks for it
            // here, so both sides name the same directory the store lives in.
            dataDir = dataRoot.absolutePath,
        )
    }

    /** Loads persisted state once and keeps the language table in sync. */
    fun start(scope: CoroutineScope) {
        if (!engineAvailable) return
        bridgeScope = scope
        // The certificate has to exist before anything asks whether it is
        // trusted: the core reads its own PEM to answer that, and a process
        // that has not minted one yet answers "no" about a certificate it has
        // never looked at. Android does this in its Application; the desktop
        // has the same hour, and hydration acts on the answer.
        coreEngine.ensureCa()
        StartupClock.mark("ca ready")
        // The Windows redirect loads a DLL and a driver; both travel inside this
        // jar, and both have to be on disk before the driver is asked for. The
        // call is a no-op on Linux and Android.
        WinDivertFiles.install()
        StartupClock.mark("windivert files installed")
        // On Windows this is the difference between an app that filters and an
        // app that looks like it does: say it once, in the log, before the
        // driver refuses to open and the reason has to be reconstructed.
        if (isWindowsHost() && !privileges.isElevated()) {
            System.err.println(
                "[desktop] not elevated: WinDivert will refuse to load. Start AdFree as Administrator, " +
                    "or let the logon task (schtasks /RL HIGHEST) start it.",
            )
        }
        repository.hydrateState()
        StartupClock.mark("state hydrated")
        scope.launch {
            repository.settings.map { it.isProtectionActive }.collect { protectionWanted.value = it }
        }
        scope.launch {
            repository.settings.map { it.appLanguage }.collect { L10n.language = it }
        }
        scope.launch(Dispatchers.IO) {
            repository.settings.map { it.autoStart }.distinctUntilChanged().collect { enabled ->
                // Read on every change rather than once: the setting can be
                // turned on before or after the protection unit exists, and
                // what the entry starts depends on that answer.
                val argv = privileges.selfArgv()
                if (argv.isNullOrEmpty()) {
                    // A switch that turns green and writes nothing is the worst
                    // of the answers: say why instead of returning quietly.
                    System.err.println("[desktop] autostart: cannot read this process's own command line")
                    return@collect
                }
                autostart.sync(
                    enabled = enabled,
                    unitInstalled = privileges.privilegedServiceInstalled(),
                    appArgv = argv,
                    workingDirectory = System.getProperty("user.dir"),
                ).onFailure { System.err.println("[desktop] autostart: ${it.message}") }
            }
        }
        L10n.systemLanguage = platformHost.deviceLanguage()
        // The browser extension is a client of this window, not of a second
        // copy of the app: it listens on the runtime directory and answers
        // from the same state the screens read.
        assistantBridge.start(scope)
        applyConfig()
    }

    /**
     * Filtering the machine's own traffic on a desktop is worth nothing while
     * TLS stays sealed, so HTTPS is the default here wherever the certificate
     * can be made trusted: the CA goes into the system store through polkit,
     * the same authority the redirect rules are asked of, and filtering comes
     * on after it. Once per install, because a switch the user later turns off
     * must stay off; Settings keeps offering the same ask by hand.
     *
     * The shell calls this rather than [start], and on its IO thread: the
     * elevation dialog blocks until it is answered, and a headless test has no
     * business raising one.
     */
    fun applyHttpsDefault(scope: CoroutineScope) {
        if (!engineAvailable) return
        if (store.getBoolean(KEY_HTTPS_DEFAULT_APPLIED, false)) return
        scope.launch(Dispatchers.IO) {
            if (!coreEngine.isCaTrusted()) {
                val reason = platformHost.installSystemCa()
                // The store is the authority, not the dialog: an ask that
                // reported a refusal after the certificate had landed is a
                // success, and one that came back clean while nothing is
                // trusted must not switch HTTPS on. An untrusted certificate
                // would make the engine a plain relay wearing a filtering
                // switch.
                if (!coreEngine.isCaTrusted()) {
                    if (reason != null) platformHost.toast(reason)
                    // The default is not spent on a failure. A flag written
                    // before the attempt turned one bad start into the end of
                    // the feature: on Windows the certificate could not be
                    // installed at all, and the app never asked again.
                    return@launch
                }
            }
            store.putBoolean(KEY_HTTPS_DEFAULT_APPLIED, true)
            if (!repository.settings.value.isHttpsFilteringEnabled) {
                repository.toggleHttpsFiltering(true)
            }
        }
    }

    /**
     * Connects the browser extension without being asked.
     *
     * The bridge is not a choice a user makes, it is a fact about the install:
     * AdGuard's Assistant is an extension with no engine, and the only way it can
     * know this machine is filtered is the manifest that points it at our host.
     * Leaving that for a "Kết nối" button meant the toolbar icon said "AdGuard is
     * not installed or configured incorrectly" on a machine that was filtering
     * perfectly, with the fix one click away and no reason to suspect it.
     *
     * Runs on every start rather than once: `installedAndUsable` reads the
     * manifest back, so an upgrade that widens `allowed_origins` — or a browser
     * installed after us — is repaired without a flag to reset. Writing a
     * manifest that is already right is a no-op the browser never notices.
     */
    fun applyBridgeDefault() {
        val host = NativeMessagingHosts.findHostBinary() ?: return
        if (NativeMessagingHosts.installedAndUsable()) return
        val written = NativeMessagingHosts.install(host)
        if (written.isNotEmpty()) {
            System.err.println(
                "[desktop] browser bridge: manifest written for ${written.size} browser(s)",
            )
        }
    }

    /**
     * Log allowed requests by default, once.
     *
     * The phone default is off — a mobile log that records everything it lets
     * through is battery spent to answer a question a phone user rarely asks —
     * but this is the machine whose window exists to be looked at, and a log
     * that only holds refusals cannot answer "what did this page ask for".
     * Once, and only once: a user who turns it off keeps it off.
     */
    fun applyLogAllowedDefault() {
        if (!engineAvailable) return
        if (store.getBoolean(KEY_LOG_ALLOWED_DEFAULT_APPLIED, false)) return
        store.putBoolean(KEY_LOG_ALLOWED_DEFAULT_APPLIED, true)
        if (!repository.settings.value.logAllowedRequests) {
            repository.toggleLogAllowed(true)
        }
    }

    fun applyConfig(): ConfigOutcome {
        coreEngine.setSystemDns(systemDnsServers())
        val outcome = coreEngine.applyConfig(privateDnsStrict = false)
        _configWarning.value = outcome.warning
        return outcome
    }

    /** The core's fallback resolver retries through the system's own servers. */
    private fun systemDnsServers(): List<String> = runCatching {
        File("/etc/resolv.conf").readLines().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "nameserver" && it.contains('.') }
        }
    }.getOrDefault(emptyList())

    private companion object {
        /** Set the first time the HTTPS default is applied, so it is never re-asked. */
        const val KEY_HTTPS_DEFAULT_APPLIED = "https_default_applied"
        /** Set the first time the log's "record allowed" default is applied. */
        const val KEY_LOG_ALLOWED_DEFAULT_APPLIED = "log_allowed_default_applied"
    }
}
