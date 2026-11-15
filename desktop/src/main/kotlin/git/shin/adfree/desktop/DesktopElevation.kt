package git.shin.adfree.desktop

import java.io.File

/**
 * The consent dialog, as code.
 *
 * Windows grants Administrator to a process only through the secure desktop's
 * prompt; there is no `sudo`, no `pkexec` and no group to join. Two very
 * different callers need one: the certificate helper (which must wait and report
 * the child's own status) and the app itself (which is the child, and must not be
 * waited for). [script] writes both, and both are PowerShell a machine can read:
 * the arguments are paths under `C:\Program Files`, so they go into an
 * `-ArgumentList` array rather than into a command line that would need a second
 * round of quoting whose failure would look like a broken certificate.
 *
 * [DENIED] is `pkexec`'s "the person said no", so a refusal reads the same way on
 * both platforms.
 */
internal object WindowsElevation {

    /** `pkexec`'s cancelled-dialog status. */
    const val DENIED = 126

    /** A PowerShell single-quoted literal: nothing interpolates, `'` doubles. */
    fun quote(text: String): String = "'" + text.replace("'", "''") + "'"

    /**
     * `waitForExit` is the difference between a helper and the app: the helper is
     * a short-lived child whose status is the answer, while the app is the thing
     * being started — waiting on it would leave the un-elevated parent sitting in
     * a dialog until the elevated app is closed.
     */
    fun script(argv: List<String>, waitForExit: Boolean): String {
        val file = quote(argv.first())
        val args = argv.drop(1).joinToString(", ") { quote(it) }
        return buildString {
            appendLine("\$ErrorActionPreference = 'Stop'")
            appendLine("try {")
            if (waitForExit) {
                appendLine("  \$p = Start-Process -FilePath $file -ArgumentList @($args) -Verb RunAs -PassThru -Wait")
                appendLine("  exit \$p.ExitCode")
            } else {
                appendLine("  Start-Process -FilePath $file -ArgumentList @($args) -Verb RunAs | Out-Null")
                appendLine("  exit 0")
            }
            appendLine("} catch {")
            appendLine("  Write-Output \"elevation refused: \$(\$_.Exception.Message)\"")
            appendLine("  exit $DENIED")
            appendLine("}")
        }
    }

    /** Runs [argv] elevated through the dialog, answering the child's status. */
    fun run(
        argv: List<String>,
        waitForExit: Boolean,
        powershell: String = powershellExe(),
    ): RunResult {
        val script = runCatching { File.createTempFile("adfree-elevate", ".ps1") }
            .getOrElse { return RunResult.Failed("cannot write the elevation script: ${it.message}") }
        return try {
            script.writeText(script(argv, waitForExit), Charsets.US_ASCII)
            val process = ProcessBuilder(
                powershell,
                "-NoProfile",
                "-NonInteractive",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                script.absolutePath,
            ).redirectErrorStream(true).start()
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().readText().trim()
            RunResult(process.waitFor(), output)
        } catch (failure: Exception) {
            RunResult.Failed(failure.message ?: failure.javaClass.simpleName)
        } finally {
            script.delete()
        }
    }

    /** The status and words of an elevated child, or why it never started. */
    data class RunResult(val code: Int, val output: String) {
        val ok: Boolean get() = code == 0
        val denied: Boolean get() = code == DENIED

        companion object {
            fun Failed(reason: String) = RunResult(-1, reason)
        }
    }

    /** `powershell.exe` as the shell has it — always present on Windows 11. */
    fun powershellExe(root: String? = System.getenv("SystemRoot")): String =
        File(root ?: "C:\\Windows", "System32/WindowsPowerShell/v1.0/powershell.exe").absolutePath
}

/** What starting the app again with Administrator came to. */
sealed interface ElevationHandOff {
    /** This process is already Administrator, or the platform does not need it. */
    data object Unnecessary : ElevationHandOff

    /**
     * An elevated copy is on its way — either through the logon task, which
     * carries the token without asking, or through the dialog. This process's
     * job is to get out of the way before the copy reads the instance lock.
     */
    data class Started(val how: String) : ElevationHandOff

    /** Nothing could be started, and the reason says why. */
    data class Failed(val reason: String) : ElevationHandOff
}

/**
 * Starts the app again with the authority its filtering needs.
 *
 * On Windows nothing about the network filter works without Administrator: the
 * driver refuses to load, so an un-elevated app is an app that installs, shows a
 * green shield and filters nothing. The two ways to get the token are not equal:
 *
 *  - the logon task, written by "start with the system" and created with
 *    `HIGHEST`, starts an elevated copy **without a prompt** — the consent was
 *    given once, when the task was created. Asking for it again at every launch
 *    would be a dialog the person has already answered.
 *  - the consent dialog, for a machine where no task was ever written. It is the
 *    same prompt an installer shows, and a refusal is answered, not an error.
 *
 * Both are skipped on a Gradle run: the test worker cannot be started on its own,
 * so elevating it would replace a running app with a window that never appears.
 */
internal class DesktopElevation(
    private val isWindows: Boolean = isWindowsHost(),
    private val isElevated: () -> Boolean = { DesktopPrivileges().isElevated() },
    private val taskInstalled: () -> Boolean = { DesktopAutostart().installed() },
    private val run: (List<String>) -> Boolean = ::runQuietly,
    private val elevate: (List<String>) -> WindowsElevation.RunResult = {
        WindowsElevation.run(it, waitForExit = false)
    },
    private val selfArgv: () -> List<String> = ::currentArgv,
) {

    fun handOff(): ElevationHandOff {
        if (!isWindows) return ElevationHandOff.Unnecessary
        if (isElevated()) return ElevationHandOff.Unnecessary
        val argv = selfArgv().ifEmpty {
            return ElevationHandOff.Failed("cannot read this process's own command line")
        }
        unstartableElevation(argv)?.let { return ElevationHandOff.Failed(it) }
        // Silent first: a Task Scheduler task created with HIGHEST already holds
        // the grant, so the dialog would only be asking for something given.
        if (taskInstalled() && run(runTaskArgv())) {
            return ElevationHandOff.Started("the logon task")
        }
        val result = elevate(argv)
        return when {
            result.ok -> ElevationHandOff.Started("the consent dialog")
            result.denied -> ElevationHandOff.Failed(
                "Administrator was refused; filtering needs it, so nothing is being blocked",
            )
            else -> ElevationHandOff.Failed(result.output.ifBlank { "exit code ${result.code}" })
        }
    }

    /**
     * The instance "start with the system" wrote, started now.
     *
     * `schtasks /Run` on a task that carries the highest token starts it in this
     * session with that token and no prompt — which is why the task, and not the
     * registry, is how this app is meant to start on Windows.
     */
    internal fun runTaskArgv(): List<String> =
        listOf("schtasks", "/Run", "/TN", DesktopAutostart.TASK_NAME)

    internal companion object {
        /**
         * A command that cannot be started a second time on its own. Only the
         * Gradle worker is named: it is the one launcher that exists solely to be
         * a child of a build, and elevating it would put a second JVM in front of
         * a window nobody asked for.
         */
        fun unstartableElevation(argv: List<String>): String? =
            if (argv.any { it.contains("GradleWorkerMain") || it.contains("worker.org.gradle") }) {
                "this window was started by Gradle, whose worker cannot start again on its own"
            } else {
                null
            }
    }
}

/**
 * This process's own launcher and arguments, as the platform reports them.
 *
 * `/proc/self/cmdline` on Linux, the process table on Windows — the same question
 * with a different answer, so it is asked once here and the callers stop caring.
 */
internal fun currentArgv(): List<String> = runCatching {
    val command = ProcessHandle.current().info().command().orElse("")
    val arguments = ProcessHandle.current().info().arguments().orElse(emptyArray())
    if (command.isBlank()) emptyList() else listOf(command) + arguments
}.getOrDefault(emptyList())
