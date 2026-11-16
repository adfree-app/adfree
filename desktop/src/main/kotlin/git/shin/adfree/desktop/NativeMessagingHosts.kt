package git.shin.adfree.desktop

import java.io.File

/**
 * Puts `adfree-native-host` in front of every browser that can talk to it.
 *
 * Chrome's native messaging is not a service: a browser finds a host by
 * reading a small JSON manifest out of its own profile directory and starting
 * whatever `path` names. So "the extension can drive AdFree" means three
 * things on disk — the host binary, one manifest per browser, and the
 * extension that asks for it — and only the middle one is generated here.
 *
 * The manifest is written per browser because each one owns a different
 * profile root. A browser that is not installed is skipped rather than
 * created: an empty `BraveSoftware/` directory is how a user ends up
 * wondering why their crashed-profile dialog appeared.
 *
 * The extension itself is not ours to install. It is AdGuard's, published on
 * the Chrome Web Store and the Edge Add-ons store, and the manifest only says
 * *who may call this host*: `allowed_origins` names those extensions and
 * nothing else.
 *
 * Linux and Windows disagree about the *third* thing, and it is the one that
 * decides whether a browser ever reads the manifest. On Linux the manifest
 * lives in the browser's own profile directory. On Windows there is no such
 * lookup: the browser asks the registry under `HKCU\Software\<vendor>\
 * NativeMessagingHosts\<name>` for the path of a manifest, which may live
 * anywhere the user can read. Both halves are here because the rest of the
 * app does not care which one applies.
 */
internal object NativeMessagingHosts {

    /** The host name the extension asks Chrome to start. */
    const val HOST_NAME: String = "com.adguard.browser_extension_host.nm"

    /** AdGuard Browser Assistant, as published on the Chrome Web Store. */
    const val EXTENSION_ID: String = "fbohpolgemkbfphodcfgnpjcmedcjhpn"

    /**
     * Every id the same assistant is published under.
     *
     * An extension id is derived from the store it was uploaded to, so AdGuard's
     * own Assistant is `fboh…` in Chrome and `calilk…` in the Edge Add-ons store,
     * and AdGuard's manifest lists both (and three more) in `allowed_origins`.
     * Ours listed one: the registry key was written for Edge, Brave and Vivaldi
     * too, and in each of them the browser refused the connection and the popup
     * said AdFree was not installed. Being told "not installed" by an extension
     * that is running is the one answer this file exists to prevent.
     */
    val EXTENSION_IDS: List<String> = listOf(
        EXTENSION_ID,
        "calilkfbhgibagenlbchfbiafnacldki",
        "hhaeiccdiaojoofohjiennalnphobkaf",
        "mkphddcgnalfkgjbklbepofehokofiek",
        "lcelghcpbepaoamjahfegdgocagfgpdi",
    )

    /**
     * The manifest both platforms write.
     *
     * The path is a JSON string, and on Windows a path is mostly backslashes —
     * `"C:\Users\Admin\.cache\..."` is not JSON, because `\U` and `\.` are
     * not escapes. Chrome refuses a manifest it cannot parse and reports the host
     * as missing, so the extension's popup said "AdGuard is not installed or
     * configured incorrectly" over a file that was there and pointing at a host
     * that ran. On Unix the bug was invisible: no path there has a backslash.
     */
    fun manifestBody(host: File): String = """
        {
          "name": "$HOST_NAME",
          "description": "AdFree filtering engine for AdGuard's browser extension",
          "path": ${jsonString(host.canonicalFile.absolutePath)},
          "type": "stdio",
          "allowed_origins": [${allowedOrigins()}]
        }
    """.trimIndent() + "\n"

    /** A JSON string literal: the two characters JSON cannot carry as they are. */
    internal fun jsonString(value: String): String {
        val escaped = buildString {
            for (c in value) {
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }
        return "\"$escaped\""
    }

    /** The `allowed_origins` line, spelled once for both manifests. */
    private fun allowedOrigins(): String =
        EXTENSION_IDS.joinToString(", ") { "\"chrome-extension://$it/\"" }

    /**
     * Profile roots, relative to `$XDG_CONFIG_HOME`. Plain Chromium forks keep
     * the same layout; a browser that is not there is skipped.
     */
    private val BROWSER_ROOTS = listOf(
        "google-chrome",
        "google-chrome-beta",
        "google-chrome-unstable",
        "chromium",
        "BraveSoftware/Brave-Browser",
        "microsoft-edge",
        "microsoft-edge-beta",
        "vivaldi",
        "opera",
        "yandex-browser",
    )

    /**
     * Locates the host binary the same way the core library is located:
     * `ADFREE_NATIVE_HOST` wins, then the `cargo build --release` output under
     * the repo, read relative to the working directory.
     */
    fun findHostBinary(): File? {
        System.getenv("ADFREE_NATIVE_HOST")?.let { return File(it).takeIf(File::exists) }
        val dir = File(System.getProperty("user.dir"))
        val names = listOf("adfree-native-host", "adfree-native-host.exe")
        val candidates = names.flatMap { name ->
            listOfNotNull(
                File("rust/target/release/$name"),
                File("../rust/target/release/$name"),
                File(dir, "rust/target/release/$name"),
                dir.parentFile?.let { File(it, "rust/target/release/$name") },
                // A packaged install ships the host beside the app image.
                File(dir, "app/resources/$name"),
                dir.parentFile?.let { File(it, "app/resources/$name") },
            )
        }
        return candidates.firstOrNull(File::exists) ?: unpackedHostBinary()
    }

    /** The file name `cargo` writes: an `.exe` on Windows, a bare name elsewhere. */
    internal fun hostBinaryName(): String =
        if (isWindowsHost()) "adfree-native-host.exe" else "adfree-native-host"

    /**
     * The host that travels inside the application jar, written out where a
     * browser can start it.
     *
     * An installed package has no repository beside it and no `app/resources`
     * to look in — the MSI ships jars and a runtime — so without this the bridge
     * had no host at all on Windows and AdGuard's own extension answered "AdFree
     * is not installed" on a machine that was filtering. The core solved the
     * same problem the same way (see [unpackedLibrary]); this is the second
     * file with that shape, so it reads the resource, writes it to the same
     * cache directory and hands back the path.
     */
    internal fun unpackedHostBinary(name: String = hostBinaryName()): File? {
        val stream = object {}.javaClass.getResourceAsStream("/$name") ?: return null
        val bytes = stream.use { it.readBytes() }
        val cache = File(
            System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path,
            "adfree/lib",
        )
        cache.mkdirs()
        val target = File(cache, name)
        if (target.length() != bytes.size.toLong()) {
            val temp = File(cache, "$name.tmp")
            temp.writeBytes(bytes)
            temp.setExecutable(true)
            // Renamed rather than written in place: a half-written host is a
            // browser that starts a process which dies mid-frame, and the
            // manifest keeps pointing at it.
            temp.renameTo(target)
            target.setExecutable(true)
        }
        return target.takeIf(File::isFile)
    }

    /**
     * Where each browser looks for a host manifest in the registry, relative to
     * `HKEY_CURRENT_USER\Software`. A browser that is not installed has no key
     * there, so the list doubles as the "is it installed" test.
     */
    private val WINDOWS_BROWSERS = listOf(
        "Google\\Chrome",
        "Chromium",
        "BraveSoftware\\Brave-Browser",
        "Microsoft\\Edge",
        "Vivaldi",
        "Opera Software\\Opera Stable",
        "Yandex\\YandexBrowser",
    )

    /** `HKCU\Software\<vendor>\NativeMessagingHosts\<name>`. */
    fun windowsKey(vendor: String): String =
        "HKCU\\Software\\$vendor\\NativeMessagingHosts\\$HOST_NAME"

    /** Runs one `reg` command and reports whether it exited cleanly. */
    private fun reg(args: List<String>): Boolean = runCatching {
        val process = ProcessBuilder(listOf("reg") + args)
            .redirectErrorStream(true)
            .start()
        // The output is only interesting when it fails, and nothing here waits on
        // a dialog: `reg` is a console tool and prints and exits.
        process.inputStream.readBytes()
        process.waitFor() == 0
    }.getOrDefault(false)

    /** The value `reg query` printed for a key, or null when the key is absent. */
    private fun regValue(key: String): String? = runCatching {
        val process = ProcessBuilder("reg", "query", key, "/ve")
            .redirectErrorStream(true)
            .start()
        val text = process.inputStream.readBytes().toString(Charsets.UTF_8)
        if (process.waitFor() != 0) return@runCatching null
        // `    (Default)    REG_SZ    C:\...\x.json`
        text.lineSequence()
            .firstOrNull { it.contains("REG_SZ") }
            ?.substringAfter("REG_SZ")
            ?.trim()
    }.getOrNull()

    /** Where the manifest is kept on Windows: the app's own data directory. */
    fun windowsManifest(dataDir: File): File =
        File(dataDir, "native-messaging/$HOST_NAME.json")

    /**
     * The app's data directory — where the Windows manifest lives, because the
     * Linux half has a profile directory per browser and Windows needs one file
     * somewhere only this user can read.
     */
    fun dataDir(): File = File(
        System.getenv("XDG_DATA_HOME") ?: File(System.getProperty("user.home"), ".local/share").path,
        "adfree",
    )

    fun configHome(): File =
        File(System.getenv("XDG_CONFIG_HOME") ?: File(System.getProperty("user.home"), ".config").path)

    fun manifestFor(root: File): File = File(root, "NativeMessagingHosts/$HOST_NAME.json")

    /** Browser profile roots that exist on this machine right now. */
    fun installedBrowsers(configHome: File = configHome()): List<File> =
        BROWSER_ROOTS.map { File(configHome, it) }.filter { it.isDirectory }

    /**
     * Writes the manifest into every browser found.
     *
     * Returns the directories written, so the caller can say *which* browsers
     * now work instead of "done": the extension also has to be installed in
     * that browser, and a user who has two of them needs to know which half is
     * finished.
     */
    fun install(
        host: File,
        configHome: File = configHome(),
        dataDir: File = dataDir(),
    ): List<File> {
        if (isWindowsHost()) return installWindows(host, dataDir)
        val written = mutableListOf<File>()
        for (root in installedBrowsers(configHome)) {
            val target = manifestFor(root)
            target.parentFile?.mkdirs()
            runCatching { target.writeText(manifestBody(host)) }.onSuccess { written.add(target) }
        }
        return written
    }

    /**
     * Windows: one manifest in the app's data directory, a registry value per
     * browser pointing at it.
     *
     * `HKCU`, not `HKLM`: the app runs elevated, but the extension belongs to the
     * user, and a machine-wide key would advertise AdFree to every account on a
     * shared machine — including ones that never installed it.
     */
    private fun installWindows(host: File, dataDir: File): List<File> {
        val manifest = windowsManifest(dataDir)
        manifest.parentFile?.mkdirs()
        if (runCatching { manifest.writeText(manifestBody(host)) }.isFailure) return emptyList()
        // Every vendor key is written, not only the ones that exist: a browser
        // that is installed later picks the host up without the app being told,
        // and a key for an absent browser costs one registry value.
        val registered = WINDOWS_BROWSERS.count { vendor ->
            reg(listOf("add", windowsKey(vendor), "/ve", "/t", "REG_SZ", "/d", manifest.absolutePath, "/f"))
        }
        return if (registered > 0) listOf(manifest) else emptyList()
    }

    /** True when any browser already has a usable manifest. */
    fun installedAndUsable(configHome: File = configHome()): Boolean {
        if (isWindowsHost()) {
            return WINDOWS_BROWSERS.any { vendor ->
                val path = regValue(windowsKey(vendor)) ?: return@any false
                usableManifest(File(path))
            }
        }
        return installedBrowsers(configHome).any { root -> usableManifest(manifestFor(root)) }
    }

    /**
     * A manifest that names a host which exists *and* admits the extension.
     *
     * Both halves matter, and the second one is easy to forget: an older version
     * of this file wrote `allowed_origins` with one id, so a manifest left over
     * from an upgrade points at a perfectly good binary and still refuses the
     * assistant in every browser but Chrome. Reading the ids back is what turns
     * that into "connect again" instead of a row that says it is done and a popup
     * that says it is not.
     */
    private fun usableManifest(manifest: File): Boolean {
        if (!manifest.isFile) return false
        val text = runCatching { manifest.readText() }.getOrNull() ?: return false
        // A manifest the browser cannot parse is a manifest that does not exist,
        // however sound it looks to a regular expression. This is the check that
        // caught the unescaped Windows path: the file was there, the host ran,
        // and Chrome never read either.
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text) }
            .onFailure { return false }
        val path = Regex("\"path\"\\s*:\\s*\"([^\"]+)\"").find(text)
            ?.groupValues?.getOrNull(1)
            ?: return false
        if (!File(path).canExecute()) return false
        return EXTENSION_IDS.all { text.contains("chrome-extension://$it/") }
    }
}
