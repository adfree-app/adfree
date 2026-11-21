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
}
