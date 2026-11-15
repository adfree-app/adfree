package git.shin.adfree.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Elevation is the difference between an app that filters and an app that says
 * it does, so the decision is a pure one here and the dialog is not opened in a
 * test.
 *
 * The two paths are not interchangeable. A logon task written with `HIGHEST`
 * already holds the grant — asking again would be a prompt for something the
 * person already answered — while a machine that never wrote one has to be asked
 * through the consent dialog, and "no" is an answer that has to be reported
 * rather than raised.
 */
class DesktopElevationTest {

    private class Recorder {
        val ran = mutableListOf<List<String>>()
        val elevated = mutableListOf<List<String>>()
        var taskInstalled = true
        var taskRuns = true
        var elevatedResult = WindowsElevation.RunResult(0, "")
    }

    private fun elevation(recorder: Recorder, windows: Boolean = true, elevated: Boolean = false) =
        DesktopElevation(
            isWindows = windows,
            isElevated = { elevated },
            taskInstalled = { recorder.taskInstalled },
            run = { recorder.ran += it; recorder.taskRuns },
            elevate = { recorder.elevated += it; recorder.elevatedResult },
            selfArgv = { listOf("C:\\Program Files\\adfree\\adfree.exe") },
        )

    @Test
    fun anAdministratorIsNotAskedForWhatItAlreadyHas() {
        val recorder = Recorder()
        assertEquals(
            ElevationHandOff.Unnecessary,
            elevation(recorder, elevated = true).handOff(),
        )
        assertTrue("nothing was started", recorder.ran.isEmpty() && recorder.elevated.isEmpty())
    }

    @Test
    fun anotherPlatformIsLeftAlone() {
        val recorder = Recorder()
        assertEquals(
            ElevationHandOff.Unnecessary,
            elevation(recorder, windows = false).handOff(),
        )
        assertTrue(recorder.ran.isEmpty() && recorder.elevated.isEmpty())
    }

    /**
     * The task is the quiet path, and it must come first: the dialog would be a
     * question whose answer the task already carries.
     */
    @Test
    fun theLogonTaskIsPreferredOverTheConsentDialog() {
        val recorder = Recorder()
        val outcome = elevation(recorder).handOff()
        assertEquals(ElevationHandOff.Started("the logon task"), outcome)
        assertEquals(listOf(listOf("schtasks", "/Run", "/TN", DesktopAutostart.TASK_NAME)), recorder.ran)
        assertTrue("no dialog was shown", recorder.elevated.isEmpty())
    }

    @Test
    fun aMachineWithoutTheTaskIsAskedThroughTheDialog() {
        val recorder = Recorder().apply { taskInstalled = false }
        val outcome = elevation(recorder).handOff()
        assertEquals(ElevationHandOff.Started("the consent dialog"), outcome)
        assertEquals(
            "the elevated copy is this same launcher",
            listOf("C:\\Program Files\\adfree\\adfree.exe"),
            recorder.elevated.single(),
        )
    }

    @Test
    fun aRefusedDialogIsAnAnswerAndSaysWhatItCosts() {
        val recorder = Recorder().apply {
            taskInstalled = false
            elevatedResult = WindowsElevation.RunResult(WindowsElevation.DENIED, "elevation refused: cancelled")
        }
        val outcome = elevation(recorder).handOff()
        assertTrue(outcome is ElevationHandOff.Failed)
        val reason = (outcome as ElevationHandOff.Failed).reason
        assertTrue("it names the refusal: $reason", reason.contains("refused"))
        assertTrue("and the consequence: $reason", reason.contains("nothing is being blocked"))
    }

    /** A task that exists but will not start must not leave the app un-elevated and silent. */
    @Test
    fun aTaskThatFailsToStartFallsThroughToTheDialog() {
        val recorder = Recorder().apply {
            taskRuns = false
            elevatedResult = WindowsElevation.RunResult(0, "")
        }
        assertEquals(ElevationHandOff.Started("the consent dialog"), elevation(recorder).handOff())
        assertEquals(1, recorder.ran.size)
        assertEquals(1, recorder.elevated.size)
    }

    /** Elevating a Gradle worker would put a second JVM in front of a window nobody asked for. */
    @Test
    fun aBuildWorkerIsNotElevatedAndRestarted() {
        val recorder = Recorder()
        val outcome = DesktopElevation(
            isWindows = true,
            isElevated = { false },
            taskInstalled = { true },
            run = { recorder.ran += it; true },
            elevate = { recorder.elevated += it; WindowsElevation.RunResult(0, "") },
            selfArgv = { listOf("java", "-cp", "x", "worker.org.gradle.process.internal.worker.GradleWorkerMain") },
        ).handOff()
        assertTrue(outcome is ElevationHandOff.Failed)
        assertTrue(recorder.ran.isEmpty() && recorder.elevated.isEmpty())
    }

    /**
     * The helper waits for the child's status because that status is the answer;
     * the app must not, or the un-elevated parent would sit in a dialog until the
     * elevated app was closed.
     */
    @Test
    fun theAppIsStartedWithoutWaitingForIt() {
        val argv = listOf("C:\\Program Files\\adfree\\adfree.exe")
        val app = WindowsElevation.script(argv, waitForExit = false)
        val helper = WindowsElevation.script(argv, waitForExit = true)
        assertFalse("the app is not waited for", app.contains("-Wait"))
        assertTrue("the helper is", helper.contains("-Wait") && helper.contains("ExitCode"))
        for (script in listOf(app, helper)) {
            assertTrue("both ask for elevation", script.contains("-Verb RunAs"))
            assertTrue("and a refusal is pkexec's 126", script.contains("exit ${WindowsElevation.DENIED}"))
        }
    }

    @Test
    fun anApostropheInTheLauncherPathDoesNotEndThePowerShellString() {
        assertEquals("'C:\\Users\\o''brien\\adfree.exe'", WindowsElevation.quote("C:\\Users\\o'brien\\adfree.exe"))
        assertTrue(
            WindowsElevation.script(listOf("C:\\Users\\o'brien\\adfree.exe"), waitForExit = false)
                .contains("-FilePath 'C:\\Users\\o''brien\\adfree.exe'"),
        )
    }
}
