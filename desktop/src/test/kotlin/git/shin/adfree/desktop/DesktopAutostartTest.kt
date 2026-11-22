package git.shin.adfree.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The autostart entry is a machine-readable file another program reads, so what
 * matters is its exact text, not that a file appeared.
 */
class DesktopAutostartTest {
    @Test
    fun theSwitchWritesAndRemovesTheEntry() {
        val entry = File.createTempFile("adfree-autostart-", ".desktop")
        val autostart = DesktopAutostart(entry)
        autostart
            .sync(enabled = true, unitInstalled = false, appArgv = listOf("/usr/bin/java", "-jar", "app.jar"))
            .getOrThrow()
        assertTrue("the entry is on disk", autostart.installed())
        assertTrue("and is a desktop entry", entry.readText().startsWith("[Desktop Entry]"))
        autostart.sync(enabled = false, unitInstalled = false, appArgv = emptyList()).getOrThrow()
        assertFalse("the switch off means the file is gone", entry.isFile)
    }

    /**
     * Once the protection unit exists, login starts that unit rather than the
     * app: a window that has to hand itself over to a privileged instance every
     * morning is a window that flashes twice.
     */
    @Test
    fun anInstalledUnitIsWhatTheSessionStarts() {
        val entry = File.createTempFile("adfree-autostart-", ".desktop")
        DesktopAutostart(entry)
            .sync(
                enabled = true,
                unitInstalled = true,
                appArgv = listOf("/usr/bin/java", "-jar", "app.jar"),
            ).getOrThrow()
        val text = entry.readText()
        assertTrue("the unit, not the app: $text", "Exec=systemctl --no-block restart adfree-desktop" in text)
        assertTrue("no path is needed for a unit", "Path=" !in text)
    }

    /**
     * The entry has to carry the directory the app was installed from, because
     * a session starts it from the home directory and the core is found
     * relative to the working directory.
     */
    @Test
    fun theAppEntryCarriesTheWorkingDirectory() {
        val entry = File.createTempFile("adfree-autostart-", ".desktop")
        DesktopAutostart(entry)
            .sync(
                enabled = true,
                unitInstalled = false,
                appArgv = listOf("/usr/bin/java", "-jar", "app.jar"),
                workingDirectory = "/opt/adfree",
            ).getOrThrow()
        assertTrue("the core is found from here", "Path=/opt/adfree" in entry.readText())
    }

    /**
     * A classpath is one long word; a home directory with a space in it is not
     * one word at all unless the entry says so. `%` doubles because the spec
     * reads it as the start of a field code.
     */
    @Test
    fun aPathWithASpaceStaysOneArgument() {
        val text =
            DesktopAutostart(File("/tmp/x.desktop")).render(
                listOf("/usr/bin/java", "-cp", "/home/a b/lib/*", "MainKt", "--at=50%"),
            )
        assertTrue(
            "the space is quoted and the percent escaped: $text",
            "Exec=/usr/bin/java -cp \"/home/a b/lib/*\" MainKt --at=50%%" in text,
        )
        assertEquals(" ", " ")
    }

    /**
     * Windows has to start the app *elevated*, and only a task with the highest
     * run level does that without a UAC prompt at every logon. The task is
     * written as XML because `/TR` cannot carry a path with a space in it — see
     * [aPathUnderProgramFilesIsOneProgramWord].
     */
    @Test
    fun windowsGetsALogonTaskWrittenAsXml() {
        val file = File("C:\\Users\\Admin\\AppData\\Local\\Temp\\adfree-logon-task1.xml")
        val argv = DesktopAutostart.schtasksCreate(file)
        assertEquals("schtasks", argv.first())
        assertTrue("created, not modified", argv.contains("/Create"))
        assertEquals("adfree-desktop", argv[argv.indexOf("/TN") + 1])
        assertEquals("from the file, not from a command line", file.absolutePath, argv[argv.indexOf("/XML") + 1])
        assertFalse("`/TR` is the field that split the path", argv.contains("/TR"))
        assertTrue("repeatable", argv.contains("/F"))
    }

    /**
     * The bug this replaced, measured on the machine that would not start: Task
     * Scheduler split `C:\Program Files\adfree\adfree.exe` at the space and kept
     * `<Command>C:\Program</Command>` with the rest as arguments. `/Run` then
     * answered SUCCESS and started nothing.
     */
    @Test
    fun aPathUnderProgramFilesIsOneProgramWord() {
        val xml =
            logonTaskXml(
                command = "C:\\Program Files\\adfree\\adfree.exe",
                arguments = "",
                userId = "DESKTOP-GPK2EJ8\\Admin",
            )
        assertTrue(
            "the whole path is the command: $xml",
            "<Command>C:\\Program Files\\adfree\\adfree.exe</Command>" in xml,
        )
        assertFalse("and it is not cut at the space", "<Command>C:\\Program</Command>" in xml)
        assertTrue("no arguments are invented for it", "<Arguments>" !in xml)
        // Task Scheduler refuses a file whose bytes disagree with its declaration.
        assertTrue("UTF-16, which is what it exports", xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-16\"?>"))
    }

    @Test
    fun theTaskRunsInTheSessionWithTheHighestToken() {
        val xml = logonTaskXml("C:\\AdFree\\adfree.exe", "", "PC\\Admin")
        assertTrue("a logon trigger", "<LogonTrigger>" in xml)
        // A task without the interactive token runs where no window can be drawn.
        assertTrue("in the person's session", "<LogonType>InteractiveToken</LogonType>" in xml)
        assertTrue("with the administrator token", "<RunLevel>HighestAvailable</RunLevel>" in xml)
        assertTrue("for the whole session, not an hour of it", "<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>" in xml)
        assertTrue("the account is named", "<UserId>PC\\Admin</UserId>" in xml)
    }

    @Test
    fun anArgumentsWordWithASpaceKeepsItsQuotes() {
        val xml = logonTaskXml("C:\\AdFree\\adfree.exe", windowsTaskArguments(listOf("--dir", "C:\\a b")), "PC\\Admin")
        // The quotes are XML-escaped, which is how the same two characters reach
        // Task Scheduler: it decodes the element and hands the words on whole.
        assertTrue(
            "quoted as Task Scheduler reads it: $xml",
            "<Arguments>--dir &quot;C:\\a b&quot;</Arguments>" in xml,
        )
    }

    /** Only the five characters XML spells differently; a backslash is a backslash. */
    @Test
    fun xmlSpellsTheFiveCharactersDifferently() {
        assertEquals("a&amp;b&lt;c&gt;d&quot;e&apos;f", xmlEscape("a&b<c>d\"e'f"))
        assertEquals("C:\\Program Files\\x.exe", xmlEscape("C:\\Program Files\\x.exe"))
    }

    /** The command is read back out of the task, which is what `installed` checks. */
    @Test
    fun theCommandIsReadOutOfTheTaskXml() {
        val xml =
            "<?xml version=\"1.0\"?><Task><Actions><Exec>" +
                "<Command>C:\\Program Files\\AdFree\\adfree.exe</Command>" +
                "<Arguments>--tray</Arguments></Exec></Actions></Task>"
        assertEquals("C:\\Program Files\\AdFree\\adfree.exe", taskCommandIn(xml))
        assertEquals("nothing named, nothing read", null, taskCommandIn("<Task></Task>"))
    }

    /** A task left behind by an older install names a program that is not this one. */
    @Test
    fun aTaskPointingAtAnotherProgramIsNotThisOne() {
        assertTrue(sameProgram("C:\\Program Files\\AdFree\\adfree.exe", "c:/program files/adfree/ADFREE.EXE"))
        assertFalse(sameProgram("C:\\Program Files\\adfree\\adfree.exe", "C:\\Program"))
        assertFalse(sameProgram("", "C:\\AdFree\\adfree.exe"))
    }

    @Test
    fun aWindowsPathWithoutSpacesIsNotQuoted() {
        assertEquals(
            "C:\\AdFree\\AdFree.exe --tray",
            windowsCommandLine(listOf("C:\\AdFree\\AdFree.exe", "--tray")),
        )
    }

    @Test
    fun theWindowsSwitchDrivesTheTaskRatherThanAFile() {
        val calls = mutableListOf<List<String>>()
        val autostart =
            DesktopAutostart(
                entry = File("/nonexistent/adfree/should-not-be-written.desktop"),
                isWindows = true,
                run = { argv ->
                    calls += argv
                    true
                },
            )
        autostart
            .sync(enabled = true, unitInstalled = true, appArgv = listOf("C:\\AdFree\\AdFree.exe"))
            .getOrThrow()
        assertEquals("one task command", 1, calls.size)
        assertTrue("and it creates the task", calls.single().contains("/Create"))
        autostart
            .sync(enabled = false, unitInstalled = true, appArgv = listOf("C:\\AdFree\\AdFree.exe"))
            .getOrThrow()
        assertTrue("switching off deletes it", calls.last().contains("/Delete"))
        assertFalse("no XDG file is left behind", File("/nonexistent/adfree").exists())
    }

    /** A refused task creation must not be reported as "autostart is on". */
    @Test
    fun aRefusedTaskIsAFailure() {
        val autostart =
            DesktopAutostart(
                entry = File("/nonexistent/adfree/should-not-be-written.desktop"),
                isWindows = true,
                run = { false },
            )
        assertTrue(
            "the failure reaches the caller",
            autostart
                .sync(enabled = true, unitInstalled = true, appArgv = listOf("C:\\AdFree\\AdFree.exe"))
                .isFailure,
        )
    }
}
