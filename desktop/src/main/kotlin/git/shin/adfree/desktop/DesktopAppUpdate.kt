package git.shin.adfree.desktop

import git.shin.adfree.platform.AppUpdate
import git.shin.adfree.update.ReleaseAssets
import git.shin.adfree.update.ReleaseFeed
import git.shin.adfree.update.UpdateInfo
import git.shin.adfree.update.UpdateOutcome
import git.shin.adfree.update.UpdateUiState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Desktop
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The desktop's answer to the Settings row's check button.
 *
 * The row was wired to a stub that wrote a line to stderr and changed nothing,
 * so the button did nothing on Windows and nothing on Linux. A packaged desktop
 * build cannot be installed by the app that is running it — the MSI, the deb and
 * the package take the new file — so this checks the same release feed Android
 * checks, says which version is out, and opens the release page. That is the
 * whole job: a button that answers.
 */
class DesktopAppUpdate(
    private val currentVersion: String,
    /** Opens a URL in the user's browser; the host owns that. */
    private val openUrl: (String) -> Unit,
    /** The network read, injected so the test does not need GitHub. */
    private val latestRelease: () -> Result<Release> = { fetchLatestRelease() },
    /** The download, injected so the test does not need the file either. */
    private val download: (Asset, (Int) -> Unit) -> Result<File> = ::downloadAsset,
    /** Hands the file to the OS's installer; injected for the same reason. */
    private val openFile: (File) -> Unit = ::openWithSystem,
    /** Which of a release's assets this machine can use. */
    private val machine: ReleaseAssets.Machine = hostMachine(),
) : AppUpdate {
    /** What the feed said, which is all the screen needs to render. */
    data class Release(
        val version: String,
        val pageUrl: String,
        val notes: String,
        val assets: List<Asset> = emptyList(),
    )

    /** One file on the release: an installer, a script, a phone build. */
    data class Asset(
        val name: String,
        val url: String,
        val size: Long,
    )

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    override val state: StateFlow<UpdateUiState> = _state

    private val _outcomes = MutableSharedFlow<UpdateOutcome>(extraBufferCapacity = 4)
    override val outcomes: SharedFlow<UpdateOutcome> = _outcomes.asSharedFlow()

    /** A check in flight, so two taps cannot race two requests to one answer. */
    private val inFlight = AtomicBoolean(false)

    override fun manualCheck() {
        if (!inFlight.compareAndSet(false, true)) {
            return
        }
        _state.value = UpdateUiState.Checking
        // Off the UI thread: the row is drawn while this is in the air.
        Thread {
            try {
                latestRelease().fold(
                    onSuccess = { report(it) },
                    onFailure = {
                        System.err.println("[desktop] app update: ${it.message}")
                        _state.value = UpdateUiState.Idle
                        _outcomes.tryEmit(UpdateOutcome.Failed)
                    },
                )
            } finally {
                inFlight.set(false)
            }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Fetches the installer that fits this machine and hands it to the OS.
     *
     * The package manager still does the installing — a deb, an rpm and an MSI
     * all replace files this process is running from — but the app no longer
     * stops at a web page: it picks the right file out of the release, shows
     * the download, and starts the same dialog the user would have started by
     * hand. A release with nothing this machine can use falls back to the page.
     */
    override fun downloadAndInstall(info: UpdateInfo) {
        if (info.downloadUrl.isEmpty()) {
            openReleasePage()
            return
        }
        Thread {
            val asset = Asset(assetNameOf(info.downloadUrl), info.downloadUrl, info.expectedSize)
            _state.value = UpdateUiState.Downloading(info, 0)
            download(asset) { percent ->
                _state.value = UpdateUiState.Downloading(info, percent)
            }.fold(
                onSuccess = { file ->
                    _state.value = UpdateUiState.Available(info)
                    runCatching { openFile(file) }
                        .onSuccess { _outcomes.tryEmit(UpdateOutcome.InstallStarted) }
                        .onFailure {
                            System.err.println("[desktop] app update: cannot start the installer: ${it.message}")
                            _outcomes.tryEmit(UpdateOutcome.InstallFailed)
                        }
                },
                onFailure = {
                    System.err.println("[desktop] app update: download failed: ${it.message}")
                    _state.value = UpdateUiState.Idle
                    _outcomes.tryEmit(UpdateOutcome.InstallFailed)
                },
            )
        }.apply { isDaemon = true }.start()
    }

    private fun openReleasePage() {
        runCatching { openUrl(ReleaseFeed.RELEASES_PAGE) }
            .onSuccess { _outcomes.tryEmit(UpdateOutcome.InstallStarted) }
            .onFailure {
                System.err.println("[desktop] app update: cannot open the release page: ${it.message}")
                _outcomes.tryEmit(UpdateOutcome.InstallFailed)
            }
    }

    private fun report(release: Release) {
        if (ReleaseFeed.compare(release.version, currentVersion) > 0) {
            // The file this machine can install, not the page that lists them
            // all: the screen offers one button, and it has to know which file.
            val asset =
                ReleaseAssets
                    .pick(release.assets.map { it.name }, machine)
                    ?.first
                    ?.let { name -> release.assets.first { it.name == name } }
            _state.value =
                UpdateUiState.Available(
                    UpdateInfo(
                        version = release.version,
                        releaseNotes = release.notes,
                        downloadUrl = asset?.url.orEmpty(),
                        assetId = 0L,
                        expectedSize = asset?.size ?: 0L,
                        sha256 = "",
                    ),
                )
            _outcomes.tryEmit(UpdateOutcome.Available)
        } else {
            _state.value = UpdateUiState.Idle
            _outcomes.tryEmit(UpdateOutcome.UpToDate)
        }
    }

    private companion object {
        private val json = Json { ignoreUnknownKeys = true }

        private val client: HttpClient by lazy {
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
        }

        /**
         * The newest release, or the reason there is no answer.
         *
         * `v` is the difference between a tag and a version: the feed publishes
         * `v1.18.0`, the app runs `1.18.0`, and comparing them raw would call
         * every release newer than this build.
         */
        private fun fetchLatestRelease(): Result<Release> =
            runCatching {
                val request =
                    HttpRequest
                        .newBuilder(URI.create(ReleaseFeed.LATEST_RELEASE_API))
                        .header("Accept", "application/vnd.github+json")
                        .header("X-GitHub-Api-Version", "2022-11-28")
                        .header("User-Agent", "AdFree-desktop")
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build()
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() != 200) {
                    error("release feed answered ${response.statusCode()}")
                }
                val body = json.parseToJsonElement(response.body()).jsonObject
                val tag = body["tag_name"]?.jsonPrimitive?.content ?: error("release feed has no tag_name")
                Release(
                    version = ReleaseFeed.versionOf(tag),
                    pageUrl = body["html_url"]?.jsonPrimitive?.content ?: ReleaseFeed.RELEASES_PAGE,
                    notes = body["body"]?.jsonPrimitive?.content.orEmpty(),
                    assets =
                        body["assets"]
                            ?.jsonArray
                            ?.mapNotNull { element ->
                                val asset = element.jsonObject
                                Asset(
                                    name = asset["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                                    url =
                                        asset["browser_download_url"]?.jsonPrimitive?.content
                                            ?: return@mapNotNull null,
                                    size = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                                )
                            }.orEmpty(),
                )
            }

        /** Which machine this is: the JVM's OS and CPU, and the Linux distribution. */
        fun hostMachine() =
            ReleaseAssets.Machine(
                os = System.getProperty("os.name").orEmpty(),
                arch = System.getProperty("os.arch").orEmpty(),
                distro = distro(),
            )

        /**
         * The distribution's own name, from `/etc/os-release`.
         *
         * "Linux" is not enough to choose a package: an Arch machine installs a
         * `.pkg.tar.zst` and cannot install a deb at all, and the release has
         * both.
         */
        fun distro(): String =
            runCatching {
                val release = File("/etc/os-release")
                if (!release.canRead()) return ""
                release
                    .readLines()
                    .filter { it.startsWith("ID=") || it.startsWith("ID_LIKE=") }
                    .joinToString(" ") { it.substringAfter('=').trim('"') }
            }.getOrDefault("")

        /** The file name an asset URL ends with, which is how the OS sees it. */
        fun assetNameOf(url: String): String = url.substringAfterLast('/').substringBefore('?').ifBlank { "adfree-update" }

        /**
         * Fetches an installer to a temporary directory, reporting how much has
         * arrived.
         *
         * The size comes from the release, so progress is a real number rather
         * than a spinner: the MSI is tens of megabytes and the wait is long
         * enough that a still bar reads as a hang.
         */
        fun downloadAsset(
            asset: Asset,
            onProgress: (Int) -> Unit,
        ): Result<File> =
            runCatching {
                val dir = File(System.getProperty("java.io.tmpdir"), "adfree-update").apply { mkdirs() }
                val target = File(dir, asset.name)
                val connection =
                    (URI.create(asset.url).toURL().openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000
                        readTimeout = 120_000
                        instanceFollowRedirects = true
                        setRequestProperty("Accept", "application/octet-stream")
                        setRequestProperty("User-Agent", "AdFree-desktop")
                    }
                try {
                    if (connection.responseCode !in 200..299) {
                        error("the release answered ${connection.responseCode}")
                    }
                    connection.inputStream.use { input ->
                        target.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                                total += read
                                if (asset.size > 0) {
                                    onProgress(((total * 100) / asset.size).toInt().coerceIn(0, 100))
                                }
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
                target
            }

        /**
         * Starts the OS's own installer for the file.
         *
         * `Desktop.open` asks the file association, which is what a double click
         * does; where there is no desktop integration for the type, the file
         * manager's default handler is tried directly.
         */
        fun openWithSystem(file: File) {
            // An Arch package has no file association: double-clicking one opens
            // an archive viewer. What it needs is pacman, and pacman needs root,
            // so the password prompt the user sees is polkit's — the same one
            // the setup helper already asks for.
            if (file.name.endsWith(".pkg.tar.zst")) {
                val started =
                    ProcessBuilder("pkexec", "pacman", "-U", "--noconfirm", file.absolutePath)
                        .redirectErrorStream(true)
                        .start()
                // pacman's own output is the explanation when it fails; a bare
                // exit code would leave the user with nothing.
                val output = started.inputStream.bufferedReader().readText()
                check(started.waitFor() == 0) {
                    output.ifBlank { "pacman exited ${started.exitValue()}" }.trim()
                }
                return
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                runCatching { Desktop.getDesktop().open(file) }
                    .onSuccess { return }
            }
            val command =
                if (System.getProperty("os.name").orEmpty().startsWith("Windows")) {
                    listOf("rundll32", "url.dll,FileProtocolHandler", file.absolutePath)
                } else {
                    listOf("xdg-open", file.absolutePath)
                }
            val started = ProcessBuilder(command).redirectErrorStream(true).start()
            check(started.waitFor() == 0) { "${command.first()} exited ${started.exitValue()}" }
        }
    }
}
