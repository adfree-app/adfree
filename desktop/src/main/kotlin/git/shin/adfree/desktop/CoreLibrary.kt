package git.shin.adfree.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The core's file name on this platform: `cargo` writes a Unix shared object and
 * a Windows DLL, and everything that looks the library up has to agree with it.
 */
internal val coreLibraryName: String
    get() = if (isWindowsHost()) "adfree_core.dll" else "libadfree_core.so"

/** Where `cargo build --release` leaves the core, without the file name. */
private const val CORE_TARGET_DIR = "rust/target/release"

/**
 * Locates the host build of the Rust core, best candidate first.
 */
internal fun findCoreLibrary(): File? = coreCandidates().firstOrNull(File::exists)

/**
 * Every place the core may be, best first.
 *
 * `ADFREE_CORE_LIB` is a preference, not an instruction: the protection unit
 * carries one written at install time, and a path that names nothing — a cache
 * the machine has since cleared — must not end the search. It used to, so the
 * service came up with a window saying the core was missing while the core sat
 * inside the jar it was running from, and opening the app by hand worked
 * because that process carries no such variable. A path that names nothing is
 * skipped like any other candidate that is not there.
 */
internal fun coreCandidates(
    envPath: String? = System.getenv("ADFREE_CORE_LIB"),
    dir: File = File(System.getProperty("user.dir")),
): List<File> {
    val name = coreLibraryName
    return listOfNotNull(
        envPath?.let(::File)?.takeIf(File::isFile),
        File("$CORE_TARGET_DIR/$name"),
        File("../$CORE_TARGET_DIR/$name"),
        File(dir, "$CORE_TARGET_DIR/$name"),
        dir.parentFile?.let { File(it, "$CORE_TARGET_DIR/$name") },
        // A packaged app has no repository beside it. The launcher knows where
        // it lives, and the library was shipped next to the jars — that is the
        // only path an installed deb or rpm has.
        packagedLibrary(),
        // A distribution that packages the core separately puts it here; the
        // app is not the only thing allowed to know where the library is.
        File("/usr/lib/adfree/$name"),
        // An installed deb or rpm has no repository beside it and no separate
        // library to point at: the core travels inside the application jar and
        // is written out where it can be loaded from.
        unpackedLibrary(),
    )
}

/**
 * Writes the core that ships inside the application jar out to a stable path and
 * returns it. The `XDG_CACHE_HOME` name is kept on Windows too, where
 * `System.getProperty("user.home")` supplies the base: one cache layout is
 * easier to explain than two. Cached by content, so a package that brings a new core replaces the
 * old one and an unchanged one costs one hash.
 */
internal fun unpackedLibrary(): File? {
    // Read through a class of this jar: `Any::class.java` is a bootstrap class,
    // and its resource lookups do not see the application classpath.
    val name = coreLibraryName
    val stream = object {}.javaClass.getResourceAsStream("/$name") ?: return null
    val bytes = stream.use { it.readBytes() }
    val cache =
        File(
            System.getenv("XDG_CACHE_HOME") ?: File(System.getProperty("user.home"), ".cache").path,
            "adfree/lib",
        )
    return cacheLibrary(cache, name, bytes)
}

/**
 * Puts `bytes` at `<cache>/<name>` unless the cache already holds exactly these
 * bytes, and answers the file to load.
 *
 * Written with `Files.move`, not `File.renameTo`: a rename onto an existing file
 * fails on Windows and silently does nothing, which left an updated package
 * running the core the cache already held — the installed app answered
 * `UnsatisfiedLinkError: looking up function …` for every export the new build
 * added, from a build that shipped the right library inside its own jar.
 *
 * When the move is refused as well — on Windows a library another process holds
 * open cannot be replaced — the bytes land beside it under a name of their own.
 * A second copy of the new core is cheaper than a process running an old one.
 */
internal fun cacheLibrary(
    cache: File,
    name: String,
    bytes: ByteArray,
): File? {
    cache.mkdirs()
    val target = File(cache, name)
    if (target.isFile && holdsBytes(target, bytes)) return target
    val temp = File(cache, "$name.tmp")
    temp.writeBytes(bytes)
    temp.setExecutable(true)
    val placed =
        runCatching {
            // Move rather than write in place: a half-written library is a load
            // failure the next process inherits, and two windows starting at
            // once must not see one another's partial file.
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            target
        }.getOrNull()
            ?: runCatching {
                val spare = File(cache, "$name.${bytes.size}")
                Files.move(temp.toPath(), spare.toPath(), StandardCopyOption.REPLACE_EXISTING)
                spare
            }.getOrNull()
    placed?.setExecutable(true)
    return placed?.takeIf(File::isFile)
}

/**
 * Whether the file already holds these bytes.
 *
 * A length is not an identity: two builds of the core a few instructions apart
 * land on the same page-aligned size, and a cache keyed on it answered "the
 * core is already here" for a library from the previous release. Every fix in
 * the new build then sat unloaded inside the package that shipped it: 1.33.3
 * was installed, and the process it started still answered `AAAA` records on a
 * machine with no IPv6 route, because the 1.33.2 core was the one the loader
 * was handed.
 *
 * Fourteen megabytes hashed once per start is the cost of a cache that means
 * what it says. A file that cannot be read is not the file this build ships.
 */
private fun holdsBytes(
    file: File,
    bytes: ByteArray,
): Boolean {
    if (file.length() != bytes.size.toLong()) return false
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    val cached =
        runCatching {
            file.inputStream().use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest()
        }.getOrNull() ?: return false
    return cached.contentEquals(
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes),
    )
}

/**
 * The core beside a packaged app image: `jpackage` sets `jpackage.app-path` to
 * the launcher in `bin/`, and the app resources land in `lib/app/resources/`.
 */
private fun packagedLibrary(): File? {
    val launcher = System.getProperty("jpackage.app-path")?.let(::File) ?: return null
    val image = launcher.parentFile?.parentFile ?: return null
    return File(image, "lib/app/resources/$coreLibraryName")
}
