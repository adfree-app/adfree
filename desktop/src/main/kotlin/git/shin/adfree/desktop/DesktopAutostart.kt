package git.shin.adfree.desktop

import java.io.File

/**
 * "Start with the system", for a desktop session.
 *
 * A graphical login is the only moment a window has a display to open on, so
 * the entry lives in XDG's autostart directory rather than in a boot-time
 * system unit: the unit that holds `CAP_NET_ADMIN` is started by whatever runs
 * at login, and a session that is not logged in has nothing for it to protect.
 *
 * What it starts depends on how far the machine has been set up. With the
 * protection unit installed, the entry starts the unit — no window, no second
 * instance, and the switch the user left on is a machine that is filtered from
 * the first second of the session. Without it, the entry starts the app, which
 * is what installs the unit the first time somebody asks for full protection.
 */
class DesktopAutostart(
    private val entry: File = defaultEntry(),
    /**
     * How this machine starts things at logon.
     *
     * XDG on Linux: one file, no privilege, and it may point at the unit. On
     * Windows the app must also start *elevated*, and a `Run` key cannot do
     * that — the token is only granted by the secure desktop's prompt, which
     * means a UAC dialog at every logon. A Scheduled Task with `ONLOGON` and
     * `HIGHEST` starts in the user's session without that prompt, which is why
     * it, and not the registry, is what this writes.
     */
    private val isWindows: Boolean = isWindowsHost(),
    private val run: (List<String>) -> Boolean = ::runQuietly,
) {
    /** Writes or removes the entry, whichever the setting asks for. */
    fun sync(
        enabled: Boolean,
        unitInstalled: Boolean,
        appArgv: List<String>,
        workingDirectory: String? = null,
    ): Result<Unit> =
        runCatching {
            if (isWindows) {
                if (!enabled) {
                    run(schtasksDelete())
                    return@runCatching
                }
                val argv = appArgv.ifEmpty { return@runCatching }
                // The task is written as XML, not as `/TR`: `schtasks /TR` keeps the
                // value as one string and Task Scheduler splits it at the first
                // space, so a program under `C:\Program Files` became the command
                // `C:\Program` with the rest as its arguments — a task that answers
                // `/Run` with SUCCESS and starts nothing. Measured on the box that
                // would not launch: `<Command>C:\Program</Command>`.
                val xml =
                    logonTaskXml(
                        command = argv.first(),
                        arguments = windowsTaskArguments(argv.drop(1)),
                        userId = logonTaskUser(),
                        workingDirectory = workingDirectory,
                    )
                val file = File.createTempFile("adfree-logon-task", ".xml")
                try {
                    // Task Scheduler reads the declaration and refuses a file whose
                    // bytes disagree with it (`unable to switch the encoding`, then
                    // `incorrect document syntax` for the UTF-8 attempt): the
                    // export it writes is UTF-16, so the file is UTF-16 with a BOM
                    // and the declaration says so. Measured on the box.
                    file.writeText("\uFEFF$xml", Charsets.UTF_16LE)
                    if (!run(schtasksCreate(file))) {
                        error("schtasks refused to create the logon task")
                    }
                } finally {
                    file.delete()
                }
                return@runCatching
            }
            if (!enabled) {
                entry.delete()
                return@runCatching
            }
            entry.parentFile?.mkdirs()
            entry.writeText(
                if (unitInstalled) {
                    render(unitStart)
                } else {
                    // The app is started from the session's own directory, which is
                    // the home directory; the core is found relative to the working
                    // directory, so the entry has to carry the one the app was
                    // installed from.
                    render(appArgv, workingDirectory)
                },
            )
        }

    /**
     * Whether this machine starts this program at logon.
     *
     * A task with the right name is not the same as the right task: Task
     * Scheduler keeps the name after the install moves, and a task whose action
     * names a path that no longer exists answers `/Run` with SUCCESS and starts
     * nothing — a launch that looks like nothing happened, which is the failure
     * the hand-off reads as "already started". The action is what is checked.
     */
    fun installed(): Boolean =
        if (isWindows) {
            val xml = runCapturing(schtasksQueryXml()) ?: return false
            val command = taskCommandIn(xml) ?: return false
            sameProgram(command, thisProgram())
        } else {
            entry.isFile
        }

    /** The launcher this process was started with, as the platform reports it. */
    private fun thisProgram(): String = currentArgv().firstOrNull().orEmpty()

    /**
     * The entry's text. `Exec` is the one field with syntax in it: words are
     * separated by spaces, and a word that holds a space, a quote, a backtick or
     * a `$` has to be quoted and escaped — the classpath of a packaged app is
     * one long word with colons, but a path in a home directory is not.
     */
    internal fun render(
        exec: List<String>,
        workingDirectory: String? = null,
    ): String {
        val commandLine = exec.joinToString(" ") { desktopQuote(it) }
        return buildString {
            appendLine("[Desktop Entry]")
            appendLine("Type=Application")
            appendLine("Name=AdFree")
            appendLine("Comment=Filtering starts with the session")
            appendLine("Exec=$commandLine")
            workingDirectory?.takeIf { it.isNotBlank() }?.let { appendLine("Path=$it") }
            appendLine("Terminal=false")
            appendLine("NoDisplay=true")
            appendLine("X-GNOME-Autostart-enabled=true")
            appendLine("X-KDE-autostart-after=panel")
        }
    }

    /** The task's name in Task Scheduler. Fixed, so `install` replaces itself. */
    internal companion object {
        const val TASK_NAME = "adfree-desktop"

        val unitStart = listOf("systemctl", "--no-block", "restart", "adfree-desktop")

        /**
         * `schtasks /Create` for a logon task that runs with the highest
         * available token. `/F` overwrites, because the app's own path changes
         * when it is updated and an old action would point at nothing.
         *
         * `/IT` is what puts the task in the person's own session: a task
         * without it runs where no window can be drawn, which for an app whose
         * whole point is a window with a shield in it is a start that looks like
         * nothing happened. The token is carried either way — `/RL HIGHEST` is
         * the grant, and it is why this task can be started silently later.
         */
        internal fun schtasksCreate(xml: File): List<String> =
            listOf(
                "schtasks",
                "/Create",
                "/TN",
                TASK_NAME,
                "/XML",
                xml.absolutePath,
                "/F",
            )

        internal fun schtasksDelete(): List<String> = listOf("schtasks", "/Delete", "/TN", TASK_NAME, "/F")

        internal fun schtasksQuery(): List<String> = listOf("schtasks", "/Query", "/TN", TASK_NAME)

        internal fun schtasksQueryXml(): List<String> = listOf("schtasks", "/Query", "/TN", TASK_NAME, "/XML")

        fun defaultEntry(): File =
            File(
                System.getenv("XDG_CONFIG_HOME")
                    ?: File(System.getProperty("user.home"), ".config").absolutePath,
                "autostart/adfree-desktop.desktop",
            )
    }
}

/**
 * One command line as Task Scheduler's `/TR` reads it: words joined by spaces,
 * quoted only when they need it, because the value is one argv element of
 * `schtasks` rather than an argv of its own.
 */
internal fun windowsCommandLine(argv: List<String>): String =
    argv.joinToString(" ") { word -> if (word.any { it.isWhitespace() }) "\"$word\"" else word }

/** Runs a command and answers whether it succeeded, with nothing on the console. */
internal fun runQuietly(argv: List<String>): Boolean =
    runCatching {
        ProcessBuilder(argv).redirectErrorStream(true).start().waitFor() == 0
    }.getOrDefault(false)

/** Runs a command and answers what it printed, or null when it failed. */
internal fun runCapturing(argv: List<String>): String? =
    runCatching {
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        if (process.waitFor() == 0) output else null
    }.getOrNull()

/**
 * The arguments of a task, as one string.
 *
 * Space-joined and quoted word by word, the same reading `Windows` gives a
 * command line: Task Scheduler hands the program its `<Arguments>` verbatim,
 * so a word with a space in it has to carry its own quotes here.
 */
internal fun windowsTaskArguments(argv: List<String>): String = windowsCommandLine(argv)

/**
 * The account a logon task runs as.
 *
 * Named rather than taken from a SID: `schtasks /XML` resolves an account name
 * itself, and a machine joined to no domain spells the same name two ways, so
 * the domain half is only prefixed when the platform reports one.
 */
internal fun logonTaskUser(
    domain: String = System.getenv("USERDOMAIN").orEmpty(),
    name: String = System.getenv("USERNAME") ?: System.getProperty("user.name").orEmpty(),
): String = if (domain.isBlank() || name.contains('\\')) name else "$domain\\$name"

/**
 * The logon task, as Task Scheduler reads it.
 *
 * `InteractiveToken` is the session: a task without it runs where no window can
 * be drawn. `HighestAvailable` is the grant that lets the app start elevated
 * without a prompt at every logon. Both are the reason this is a task and not a
 * registry value.
 */
internal fun logonTaskXml(
    command: String,
    arguments: String,
    userId: String,
    workingDirectory: String? = null,
): String =
    buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-16\"?>")
        append("<Task version=\"1.2\" xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\">")
        append("<RegistrationInfo><Description>AdFree starts with the session</Description></RegistrationInfo>")
        append("<Triggers><LogonTrigger><Enabled>true</Enabled>")
        append("<UserId>").append(xmlEscape(userId)).append("</UserId></LogonTrigger></Triggers>")
        append("<Principals><Principal id=\"Author\">")
        append("<UserId>").append(xmlEscape(userId)).append("</UserId>")
        append("<LogonType>InteractiveToken</LogonType>")
        append("<RunLevel>HighestAvailable</RunLevel></Principal></Principals>")
        append("<Settings>")
        append("<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>")
        append("<DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>")
        append("<StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>")
        append("<AllowHardTerminate>true</AllowHardTerminate>")
        append("<StartWhenAvailable>false</StartWhenAvailable>")
        append("<AllowStartOnDemand>true</AllowStartOnDemand>")
        append("<Enabled>true</Enabled><Hidden>false</Hidden><RunOnlyIfIdle>false</RunOnlyIfIdle>")
        // A filter runs for as long as the session does; the default hour would end
        // it, and an ended task is a machine that stops being filtered on its own.
        append("<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>")
        append("</Settings>")
        append("<Actions Context=\"Author\"><Exec>")
        append("<Command>").append(xmlEscape(command)).append("</Command>")
        if (arguments.isNotBlank()) {
            append("<Arguments>").append(xmlEscape(arguments)).append("</Arguments>")
        }
        if (!workingDirectory.isNullOrBlank()) {
            append("<WorkingDirectory>").append(xmlEscape(workingDirectory)).append("</WorkingDirectory>")
        }
        append("</Exec></Actions></Task>")
    }

/** The five characters XML spells differently, and nothing else is touched. */
internal fun xmlEscape(value: String): String =
    buildString {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(character)
            }
        }
    }

/** The program a task's XML names, or null when it names none. */
internal fun taskCommandIn(xml: String): String? =
    Regex("<Command>(.*?)</Command>", RegexOption.DOT_MATCHES_ALL)
        .find(xml)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?.ifBlank { null }

/**
 * Whether two paths name the same file, however each was spelled.
 *
 * Windows is case-insensitive and takes either separator, and a task written by
 * an older install spells the same directory with a different capital.
 */
internal fun sameProgram(
    a: String,
    b: String,
): Boolean {
    fun normalise(path: String) = path.replace('/', '\\').trim().trimEnd('\\')
    return a.isNotBlank() && b.isNotBlank() && normalise(a).equals(normalise(b), ignoreCase = true)
}

/**
 * One `Exec` word as the desktop-entry spec reads it. Reserved characters are
 * escaped by a backslash, and `%` doubles because it introduces a field code
 * (`%f`, `%U`) even inside quotes.
 */
internal fun desktopQuote(value: String): String {
    val escaped =
        buildString {
            value.forEach { character ->
                when (character) {
                    '\\', '"', '`', '$' -> append('\\').append(character)
                    else -> append(character)
                }
            }
        }.replace("%", "%%")
    return if (escaped.any { it.isWhitespace() || it == '\\' || it == '"' }) "\"$escaped\"" else escaped
}
