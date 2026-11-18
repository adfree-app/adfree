package git.shin.adfree.desktop

import git.shin.adfree.platform.AppUpdate
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
) : AppUpdate {
    /** What the feed said, which is all the row needs to render. */
    data class Release(
        val version: String,
        val pageUrl: String,
        val notes: String,
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

    override fun downloadAndInstall(info: UpdateInfo) {
        // There is nothing to download here: the file this machine has was put
        // there by a package manager, and the release page is where its
        // replacement is described and published.
        runCatching { openUrl(ReleaseFeed.RELEASES_PAGE) }
            .onSuccess { _outcomes.tryEmit(UpdateOutcome.InstallStarted) }
            .onFailure {
                System.err.println("[desktop] app update: cannot open the release page: ${it.message}")
                _outcomes.tryEmit(UpdateOutcome.InstallFailed)
            }
    }

    private fun report(release: Release) {
        if (ReleaseFeed.compare(release.version, currentVersion) > 0) {
            _state.value =
                UpdateUiState.Available(
                    UpdateInfo(
                        version = release.version,
                        releaseNotes = release.notes,
                        downloadUrl = release.pageUrl,
                        assetId = 0L,
                        expectedSize = 0L,
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
                )
            }
    }
}
