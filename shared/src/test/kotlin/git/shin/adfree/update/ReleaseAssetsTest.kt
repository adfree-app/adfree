package git.shin.adfree.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseAssetsTest {
    private val linuxArm = ReleaseAssets.Machine("linux", "aarch64")
    private val linuxX64 = ReleaseAssets.Machine("linux", "amd64")
    private val windows = ReleaseAssets.Machine("windows", "x86_64")

    private val release =
        listOf(
            "adfree-extra.user.js",
            "adfree-youtube.user.js",
            "app-release.apk",
            "app-release.aab",
            "adfree_1.26.0_arm64.deb",
            "adfree_1.26.0_amd64.deb",
            "adfree-1.26.0-1.aarch64.rpm",
            "adfree-1.26.0-1.x86_64.rpm",
            "adfree-1.26.0.msi",
        )

    /** The release as it stands, with every family on it. */
    private val archRelease =
        listOf(
            "adfree-1.27.0-1-x86_64.pkg.tar.zst",
            "adfree-1.27.0-1.aarch64.rpm",
            "adfree-1.27.0-1.x86_64.rpm",
            "adfree_1.26.0_arm64.deb",
            "adfree_1.26.0_amd64.deb",
            "app-release.apk",
        )

    @Test
    fun theArmMachineGetsTheArmPackage() {
        assertEquals("adfree_1.26.0_arm64.deb", ReleaseAssets.pick(release, linuxArm)?.first)
        assertEquals("adfree-1.26.0-1.aarch64.rpm", ReleaseAssets.pick(listOf("adfree-1.26.0-1.aarch64.rpm"), linuxArm)?.first)
    }

    @Test
    fun theIntelMachineGetsTheIntelPackage() {
        assertEquals("adfree_1.26.0_amd64.deb", ReleaseAssets.pick(release, linuxX64)?.first)
    }

    @Test
    fun theScriptsAndThePhoneBuildAreNotDesktopUpdates() {
        // Their names are on the same release; none of them can be installed here.
        assertNull(ReleaseAssets.pick(listOf("adfree-extra.user.js", "app-release.apk"), linuxX64))
        assertNull(ReleaseAssets.pick(release.filter { !it.endsWith(".msi") }, windows))
    }

    @Test
    fun windowsTakesTheMsi() {
        assertEquals("adfree-1.26.0.msi", ReleaseAssets.pick(release, windows)?.first)
    }

    @Test
    fun aReleaseWithOnlyTheOtherArchitectureIsNotAnUpdate() {
        val armOnly = release.filter { "arm64" in it || "aarch64" in it }
        assertNull(ReleaseAssets.pick(armOnly, linuxX64))
    }

    @Test
    fun archGetsTheArchPackageAndNotADeb() {
        val cachy = ReleaseAssets.Machine("Linux", "amd64", distro = "cachyos arch")
        assertEquals(
            "adfree-1.27.0-1-x86_64.pkg.tar.zst",
            ReleaseAssets.pick(archRelease, cachy)?.first,
        )
    }

    @Test
    fun theDebugPackageIsNotTheApp() {
        val cachy = ReleaseAssets.Machine("Linux", "amd64", distro = "arch")
        assertEquals(
            "adfree-1.27.0-1-x86_64.pkg.tar.zst",
            ReleaseAssets.pick(listOf("adfree-debug-1.27.0-1-x86_64.pkg.tar.zst") + archRelease, cachy)?.first,
        )
    }

    @Test
    fun debianKeepsTheDeb() {
        val ubuntu = ReleaseAssets.Machine("Linux", "amd64", distro = "ubuntu debian")
        assertEquals("adfree_1.26.0_amd64.deb", ReleaseAssets.pick(archRelease, ubuntu)?.first)
    }

    @Test
    fun theOsNameIsTheJvmsAndNotTheFamilys() {
        val windowsEleven = ReleaseAssets.Machine("Windows 11", "amd64")
        assertEquals("adfree-1.26.0.msi", ReleaseAssets.pick(release, windowsEleven)?.first)
    }

    @Test
    fun anUnknownOsGetsNothing() {
        assertNull(ReleaseAssets.pick(release, ReleaseAssets.Machine("plan9", "mips")))
    }
}
