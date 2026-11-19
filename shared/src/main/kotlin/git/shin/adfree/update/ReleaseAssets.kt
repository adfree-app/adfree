package git.shin.adfree.update

/**
 * Which file on a release belongs to this machine.
 *
 * A release carries one installer per platform — a deb and an rpm for each
 * Linux architecture, an MSI for Windows, an APK for Android — and the app is
 * the only thing that knows which of them it can use. The names come from
 * `desktop/build.gradle.kts` and `jpackage`, so they are matched rather than
 * parsed: a miss means "no update this way", never a wrong download.
 */
object ReleaseAssets {

    /** What this build is: the OS family and the CPU it would run on. */
    data class Machine(val os: String, val arch: String)

    private val LINUX_PACKAGE = Regex("""^adfree[_-]([0-9][^-]*)[_-].*\.(deb|rpm)$""")

    /**
     * The first asset that fits, or null when the release has none for this
     * machine — a release that predates the package, an architecture nobody
     * built, a Windows release read by a Linux box.
     */
    fun pick(names: List<String>, machine: Machine): Pair<String, String>? {
        // `os.name` is "Windows 11" and "Mac OS X", not "windows" and "mac":
        // the family is somewhere in the string, never the whole of it.
        val os = machine.os.lowercase()
        val formats =
            when {
                "windows" in os -> listOf(Format(".msi", namesArch = false), Format(".exe", namesArch = true))
                "linux" in os -> listOf(Format(".deb", namesArch = true), Format(".rpm", namesArch = true))
                "mac" in os -> listOf(Format(".dmg", namesArch = false))
                else -> emptyList()
            }
        val cpu =
            when (machine.arch.lowercase()) {
                "x86_64", "amd64", "x64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> machine.arch.lowercase()
            }
        formats.forEach { format ->
            val ofFormat = names.filter { it.lowercase().endsWith(format.extension) }
            if (ofFormat.isEmpty()) return@forEach
            // An installer that names its architecture has to name this one; the
            // MSI does not name one at all, so on that format there is nothing
            // to check and the first is the answer.
            val fitting = if (format.namesArch) ofFormat.filter { carries(it, cpu) } else ofFormat
            fitting.firstOrNull()?.let { name ->
                return name to format.extension.removePrefix(".")
            }
        }
        return null
    }

    /**
     * Whether the file name says this CPU.
     *
     * `x86_64` is written `amd64` by dpkg and `x86_64` by rpm; arm is `arm64`
     * or `aarch64`. A name with neither is ambiguous, and taking it would hand
     * an arm machine an x86 installer.
     */
    fun carries(name: String, cpu: String): Boolean {
        val n = name.lowercase()
        return when (cpu) {
            "x86_64" -> "amd64" in n || "x86_64" in n || "x64" in n
            "aarch64" -> "arm64" in n || "aarch64" in n
            else -> cpu in n
        }
    }

    /** The version a Linux package name carries, for the release that has no tag in it. */
    fun versionOf(name: String): String? = LINUX_PACKAGE.find(name)?.groupValues?.get(1)

    /** One installable format on a release, and whether its names say which CPU. */
    private data class Format(val extension: String, val namesArch: Boolean)
}
