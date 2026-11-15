package git.shin.adfree.desktop

import java.io.File

/**
 * The trust stores a desktop application reads *besides* the distro bundle.
 *
 * `trust.rs` puts the CA in the system anchor directory and rehashes the
 * bundle `OpenSSL`, `GnuTLS`, `curl`, Go and Python consult. That is not the
 * whole machine: NSS-based applications (Chromium, Brave, Edge, Electron,
 * Slack, VS Code) read an NSS database, and on a distro whose `libnssckbi.so`
 * holds Mozilla's own roots rather than `p11-kit-trust` that database is the
 * only place they will ever see a local CA. Firefox keeps one database per
 * profile. The JDK keeps a `cacerts` keystore per installed JVM.
 *
 * Installing into each of these is what makes "the certificate is installed"
 * true for the applications a person actually runs, rather than a claim that
 * happens to hold only for the ones built on OpenSSL — the reason a browser
 * shows a warning over a proxy the OS reports as trusted. It also means the
 * app never has to be patched, or told an environment variable, to trust the
 * CA — which is not something a packaged application can be given.
 *
 * NSS databases belong to the signed-in user, so this runs in the app's own
 * process, not in the root helper: a root-owned `cert9.db` is a database the
 * browser can no longer write. The JDK keystore is root-owned and lives in
 * [CaInstall] instead.
 *
 * Everything here is idempotent: the nickname is removed before it is added,
 * so a re-run replaces the certificate instead of failing on a duplicate.
 */
class DesktopTrustStores(
    private val home: File = File(System.getProperty("user.home")),
    private val certutilBinary: String = certutilPath(System.getProperty("os.name").orEmpty()) ?: MISSING_CERTUTIL,
    /** Exit code plus combined output of one command; injected so tests need no `certutil`. */
    private val runner: (List<String>) -> Pair<Int, String> = ::runCommand,
    private val log: (String) -> Unit = { System.err.println("[trust] $it") },
) {

    /** One database and what happened to it. */
    data class Store(val path: String, val ok: Boolean, val detail: String = "")

    /**
     * The NSS databases to write: the shared one NSS applications read, then
     * one per Firefox profile that already has a database.
     *
     * The shared directory is created when it is absent rather than skipped: a
     * machine where no NSS application has ever run has no database *yet*, and
     * a Chromium started after the certificate is installed must still find it
     * — the directory is exactly what Chromium looks for on its first run.
     * Firefox profiles are only touched when they exist, because a stray
     * `cert9.db` in a directory Firefox does not own means nothing.
     */
    fun databases(): List<File> {
        val chromium = File(home, CHROMIUM_DB_DIR)
        val firefoxProfiles = File(home, FIREFOX_ROOT).listFiles().orEmpty()
            .filter { it.isDirectory && File(it, FIREFOX_DB).isFile }
            .sortedBy { it.name }
        return listOf(chromium) + firefoxProfiles
    }

    /**
     * Adds [pem] to every database as a trusted CA, creating the database where
     * NSS has not made one. Returns what happened to each, because "no NSS
     * tools on this machine" and "the database refused the write" are two
     * different problems and the log should not merge them.
     */
    fun install(pem: File): List<Store> {
        if (!pem.isFile) return listOf(Store(pem.path, false, "no certificate at ${pem.path}"))
        return databases().map { db ->
            runCatching {
                if (!db.isDirectory && !db.mkdirs()) {
                    return@runCatching Store(db.path, false, "cannot create ${db.path}")
                }
                if (!readable(db)) {
                    val (code, output) = runner(listOf(certutilBinary, "-N", "--empty-password", "-d", "sql:${db.path}"))
                    if (code != 0) return@runCatching Store(db.path, false, "cannot create the database: ${output.trim()}")
                }
                // `-A` fails when the nickname is taken, and a re-run after an
                // app update must replace the certificate rather than report a
                // duplicate — so the old entry goes first, failure ignored.
                runner(listOf(certutilBinary, "-D", "-n", NICKNAME, "-d", "sql:${db.path}"))
                val (code, output) = runner(
                    listOf(
                        certutilBinary, "-A",
                        "-n", NICKNAME,
                        "-t", TRUST_FLAGS,
                        "-i", pem.absolutePath,
                        "-d", "sql:${db.path}",
                    ),
                )
                if (code != 0) return@runCatching Store(db.path, false, output.trim())
                if (!listed(db)) {
                    return@runCatching Store(db.path, false, "the certificate does not read back from the database")
                }
                Store(db.path, true)
            }.getOrElse { failure ->
                Store(db.path, false, failure.message ?: failure.javaClass.simpleName)
            }
        }
    }

    /** Takes the certificate back out, for an uninstall. Failure is not fatal. */
    fun remove(): List<Store> = databases().map { db ->
        val (code, output) = runner(listOf(certutilBinary, "-D", "-n", NICKNAME, "-d", "sql:${db.path}"))
        Store(db.path, code == 0, output.trim())
    }

    /**
     * [`install`] with the outcomes logged, the shape the callers want: a
     * machine without `certutil` is a covered-by-nothing NSS store, and that is
     * worth one line rather than a failed certificate install.
     */
    fun installAndLog(pem: File) {
        val stores = install(pem)
        for (store in stores) {
            if (store.ok) {
                log("NSS database updated: ${store.path}")
            } else {
                log("NSS database skipped (${store.path}): ${store.detail}")
            }
        }
    }

    private fun readable(db: File): Boolean =
        runner(listOf(certutilBinary, "-L", "-d", "sql:${db.path}")).first == 0

    private fun listed(db: File): Boolean =
        runner(listOf(certutilBinary, "-L", "-n", NICKNAME, "-d", "sql:${db.path}")).first == 0

    companion object {
        /** The nickname AdGuard's own tooling uses for a local CA. */
        const val NICKNAME: String = "AdFree Personal CA"
        /** `C` makes it a trusted CA for TLS; the other two slots stay empty. */
        const val TRUST_FLAGS: String = "C,,"
        private const val CHROMIUM_DB_DIR = ".pki/nssdb"
        private const val FIREFOX_ROOT = ".mozilla/firefox"
        private const val FIREFOX_DB = "cert9.db"

        /**
         * The *NSS* `certutil`, which is not the `certutil` a Windows machine
         * has.
         *
         * Windows ships its own `certutil.exe` in `System32`, and it is a
         * different program with different options: handed NSS arguments it
         * answers `CertUtil: Unknown arg: -N`. Naming it in place of NSS's tool
         * — as this did for one build — turned "cannot find the tool" into
         * "cannot create the database", which is a worse lie. Mozilla ships the
         * right one beside Firefox, so that is where it is looked for; a
         * machine with no Firefox and no NSS tools has no NSS database worth
         * writing, and saying so is the honest answer.
         */
        fun certutilPath(osName: String, env: Map<String, String> = System.getenv()): String? {
            if (!osName.lowercase().startsWith("windows")) return "certutil"
            val roots = listOfNotNull(env["ProgramFiles"], env["ProgramFiles(x86)"])
            for (root in roots) {
                val candidate = File(File(root, "Mozilla Firefox"), "certutil.exe")
                if (candidate.isFile) return candidate.path
            }
            return null
        }

        /** Stands in for a tool this machine does not have; never resolves. */
        private const val MISSING_CERTUTIL: String = "certutil"

        /** What a machine without the NSS tool is told, once per database. */
        private const val NO_CERTUTIL: String =
            "NSS certutil is not installed (Firefox ships one beside its executable); the shared " +
                "NSS database on this machine is not written"

        private fun runCommand(argv: List<String>): Pair<Int, String> {
            if (argv.firstOrNull() == MISSING_CERTUTIL && certutilPath(System.getProperty("os.name").orEmpty()) == null) {
                return -1 to NO_CERTUTIL
            }
            return runCommandOnce(argv)
        }

        private fun runCommandOnce(argv: List<String>): Pair<Int, String> = runCatching {
            val process = ProcessBuilder(argv)
                .redirectErrorStream(true)
                .start()
            // `certutil` asks for a password on a database it did not create;
            // without a stdin the question arrives on this process's terminal
            // and waits there forever. Closing the stream is what says "no
            // answer is coming", and it is the one form that works everywhere:
            // `Redirect.from(/dev/null)` names a file Windows does not have, and
            // `Redirect.DISCARD` is an *output* redirect (Java answers "Redirect
            // invalid for reading: WRITE"). Both were measured on Windows 11 and
            // both left every NSS application untrusted.
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor() to output
        }.getOrElse { failure ->
            // The reason belongs in the message: "cannot run X" alone sent the
            // reader looking for a missing binary when the binary was there.
            -1 to "cannot run ${argv.firstOrNull() ?: "the command"}: ${failure.message ?: failure.javaClass.simpleName}"
        }
    }
}
