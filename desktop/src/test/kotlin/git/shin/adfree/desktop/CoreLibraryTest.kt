package git.shin.adfree.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

/**
 * Where the core is looked for, and what a path that names nothing means.
 *
 * The protection unit starts the app with `ADFREE_CORE_LIB` set to a cache path
 * written at install time. When that file is gone — a cleared cache, an upgrade
 * that moved it — the search has to go on to the copy inside the jar, or the
 * service comes up saying the core is missing while the core is in the very
 * jar it is running from.
 */
class CoreLibraryTest {
    @Test
    fun aPathThatNamesNothingIsNotTheAnswer() {
        val directory =
            java.nio.file.Files
                .createTempDirectory("adfree-core")
                .toFile()
        val missing = File(directory, "libadfree_core.so")

        val candidates = coreCandidates(envPath = missing.absolutePath, dir = directory)

        assertFalse(
            "a path that is not there is still a candidate: $candidates",
            candidates.contains(missing),
        )
        assertNotNull("the search gave up at the missing path", candidates.firstOrNull())
    }

    @Test
    fun aCoreThatIsThereIsTakenFirst() {
        val directory =
            java.nio.file.Files
                .createTempDirectory("adfree-core")
                .toFile()
        val core = File(directory, "libadfree_core.so").apply { writeBytes(byteArrayOf(0, 1, 2)) }

        val candidates = coreCandidates(envPath = core.absolutePath, dir = directory)

        assertEquals("the environment names a core that exists", core, candidates.first())
    }

    @Test
    fun noPathAtAllStillLeavesTheRepositoryAndTheJar() {
        val directory =
            java.nio.file.Files
                .createTempDirectory("adfree-core")
                .toFile()

        val candidates = coreCandidates(envPath = null, dir = directory)

        assertNotNull("nothing left to try without a path", candidates.firstOrNull())
        // The repository candidates come after the environment's, so a build
        // tree laid out beside the working directory is still found.
        assertEquals(File("rust/target/release/libadfree_core.so"), candidates.first())
    }

    @Test
    fun aNewerCoreReplacesTheOneTheCacheHolds() {
        val cache =
            java.nio.file.Files
                .createTempDirectory("adfree-cache")
                .toFile()
        val name = "libadfree_core.so"
        // An older extraction: same name, another length. A rename that refuses
        // to overwrite (Windows) used to leave this file in place and hand it to
        // the loader, which then answered "function not found" for every export
        // the new build added.
        File(cache, name).writeBytes(ByteArray(4))

        val placed = cacheLibrary(cache, name, ByteArray(9) { 7 })

        assertNotNull("the new core was not written", placed)
        assertEquals("the file to load is the new one", 9L, placed!!.length())
        assertEquals("the cache holds the new bytes", 7, placed.readBytes()[0].toInt())
    }

    @Test
    fun theSameSizeIsNotTheSameCore() {
        val cache =
            java.nio.file.Files
                .createTempDirectory("adfree-cache")
                .toFile()
        val name = "libadfree_core.so"
        // The shape the 1.33.2 core and the 1.33.3 core had: the same length,
        // different bytes. Keyed on the length, the cache kept the older build
        // and the fix inside the newer one never reached the loader.
        val target = File(cache, name).apply { writeBytes(ByteArray(6) { 1 }) }

        val placed = cacheLibrary(cache, name, ByteArray(6) { 2 })

        assertEquals(target, placed)
        assertEquals("the cache holds this build's bytes", 2, placed!!.readBytes()[0].toInt())
    }

    @Test
    fun aCoreTheCacheAlreadyHoldsIsNotRewritten() {
        val cache =
            java.nio.file.Files
                .createTempDirectory("adfree-cache")
                .toFile()
        val name = "libadfree_core.so"
        val bytes = ByteArray(6) { 1 }
        val target = File(cache, name).apply { writeBytes(bytes) }
        target.setLastModified(1_000_000_000_000L)

        val placed = cacheLibrary(cache, name, bytes)

        assertEquals(target, placed)
        assertEquals("an unchanged core costs a hash, not a rewrite", 1_000_000_000_000L, placed!!.lastModified())
    }

    @Test
    fun aCacheItCannotOverwriteStillYieldsTheNewCore() {
        val cache =
            java.nio.file.Files
                .createTempDirectory("adfree-cache")
                .toFile()
        val name = "libadfree_core.so"
        // What a held-open library looks like from here: the name cannot be
        // replaced. The bytes must still be the ones this build ships.
        File(cache, name).mkdirs()

        val placed = cacheLibrary(cache, name, ByteArray(5) { 3 })

        assertNotNull("a refusal to replace ended the search for a core", placed)
        assertEquals("the copy it fell back to is the new core", 5L, placed!!.length())
    }
}
