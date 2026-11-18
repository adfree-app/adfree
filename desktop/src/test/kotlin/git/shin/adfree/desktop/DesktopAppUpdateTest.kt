package git.shin.adfree.desktop

import git.shin.adfree.update.ReleaseFeed
import git.shin.adfree.update.UpdateOutcome
import git.shin.adfree.update.UpdateUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Settings row's check button, which used to change nothing at all.
 *
 * What it must do now: ask the feed, say which version is out, and open the
 * release page when the user asks to install — because a packaged desktop build
 * is replaced by a package manager, not by the app that is running it.
 */
class DesktopAppUpdateTest {
    private fun update(
        current: String = "1.18.0",
        answer: () -> Result<DesktopAppUpdate.Release>,
    ): Pair<DesktopAppUpdate, MutableList<String>> {
        val opened = mutableListOf<String>()
        return DesktopAppUpdate(current, openUrl = { opened += it }, latestRelease = answer) to opened
    }

    /** The check runs on its own thread, so its answer is waited for. */
    private fun awaitState(update: DesktopAppUpdate): UpdateUiState {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val state = update.state.value
            if (state !is UpdateUiState.Checking) return state
            Thread.sleep(10)
        }
        return update.state.value
    }

    @Test
    fun `a newer tag is reported as available`() {
        val (update, _) =
            update {
                Result.success(DesktopAppUpdate.Release("1.19.0", "https://example.test/v1.19.0", "notes"))
            }

        update.manualCheck()

        val state = awaitState(update)
        assertTrue("expected an available version, got $state", state is UpdateUiState.Available)
        assertEquals("1.19.0", (state as UpdateUiState.Available).info.version)
    }

    @Test
    fun `the running version is up to date`() {
        val (update, _) =
            update {
                Result.success(DesktopAppUpdate.Release("1.18.0", "https://example.test/v1.18.0", ""))
            }

        update.manualCheck()

        assertEquals(UpdateUiState.Idle, awaitState(update))
    }

    @Test
    fun `an unreachable feed ends the row, it does not leave it checking`() {
        val (update, _) = update { Result.failure(IllegalStateException("offline")) }

        update.manualCheck()

        assertEquals(UpdateUiState.Idle, awaitState(update))
    }

    @Test
    fun `install opens the release page`() {
        val (update, opened) =
            update {
                Result.success(DesktopAppUpdate.Release("1.19.0", "https://example.test/v1.19.0", ""))
            }
        update.manualCheck()
        val state = awaitState(update) as UpdateUiState.Available

        update.downloadAndInstall(state.info)

        assertEquals(listOf(ReleaseFeed.RELEASES_PAGE), opened)
    }

    @Test
    fun `the row reports the check in its outcomes`() {
        val (update, _) =
            update {
                Result.success(DesktopAppUpdate.Release("1.19.0", "https://example.test/v1.19.0", ""))
            }
        val seen = mutableListOf<UpdateOutcome>()
        val job =
            Thread {
                kotlinx.coroutines.runBlocking {
                    update.outcomes.collect { seen += it }
                }
            }.apply { isDaemon = true }
        job.start()
        // The shared flow has no replay: a value emitted before a subscriber
        // exists goes nowhere, so the collector is given a moment to attach.
        Thread.sleep(150)

        update.manualCheck()
        awaitState(update)
        val deadline = System.currentTimeMillis() + 5_000
        while (!seen.contains(UpdateOutcome.Available) && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        job.interrupt()

        assertTrue("expected Available among $seen", seen.contains(UpdateOutcome.Available))
    }
}
