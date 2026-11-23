// Linux desktop consumer of :shared: the console engine probe and a Compose
// for desktop window that renders real core results.

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.jetbrains.compose)
    alias(libs.plugins.kotlin.compose)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))
    // The native-messaging protocol AdGuard's browser extension speaks is
    // JSON; the bridge parses and builds it with the same library the rest of
    // the app uses.
    implementation(libs.kotlinx.serialization.json)
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    // The host asks libc whether this process may mark its own sockets — that
    // is what decides whether the redirect layout can be installed at all.
    implementation(libs.jna)
    // AWT's tray is XEmbed: on a Wayland session KDE owns the tray through
    // xembedsniproxy, which shows the icon but delivers no clicks, so the
    // Compose tray menu is unreachable. Talking StatusNotifierItem to the
    // session bus is what KDE actually listens to.
    implementation(libs.dbus.java.core)
    implementation(libs.dbus.java.native.unixsocket)

    testImplementation(libs.junit)
    // dbus-java reports a failed send through SLF4J only, and without a provider
    // the reason a signal never left the bus is lost.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testImplementation(compose.desktop.uiTestJUnit4)
    testImplementation(compose.desktop.currentOs)
    testImplementation(compose.materialIconsExtended)
}

// The console probe stays the default run target; the UI window is separate.
val runUi by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Launch the Compose desktop window"
    mainClass = "git.shin.adfree.desktop.DesktopAppKt"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The full AdFree UI (shared screens + desktop seams) in one window.
val runApp by tasks.registering(JavaExec::class) {
    group = "application"
    description = "Launch the full AdFree desktop app"
    mainClass = "git.shin.adfree.desktop.AdFreeDesktopKt"
    classpath = sourceSets["main"].runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// ── Linux packaging ────────────────────────────────────────────────────────
// A deb and an rpm are what an Ubuntu, Fedora or Arch user installs, and each
// carries its own runtime. The filtering core is a native library the JVM
// loads, so it travels inside the application jar as a resource and is written
// out on first run (`findCoreLibrary`). It goes in the jar rather than through
// jpackage's `--app-content` because that path is decided while the project is
// configured and fails silently when a file is staged a moment too late: the
// package built, shipped, and could not filter.
// The core is a shared object on Unix and a DLL on Windows; everything that
// looks it up — this file, `findCoreLibrary`, the loader inside the core — has
// to agree on which, so the name is derived once.
val isWindowsHost = System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")
val coreLibraryName = if (isWindowsHost) "adfree_core.dll" else "libadfree_core.so"
val coreLibrary: File = listOf(
    "rust/target/release/$coreLibraryName",
    // A GNU-targeted build on Windows lands here instead of `target/release`.
    "rust/target/x86_64-pc-windows-gnu/release/$coreLibraryName",
).map { rootProject.layout.projectDirectory.file(it).asFile }.firstOrNull { it.isFile }
    ?: rootProject.layout.projectDirectory.file("rust/target/release/$coreLibraryName").asFile

// The native-messaging host: `cargo` writes an `.exe` on Windows and a bare
// name elsewhere, and `NativeMessagingHosts` looks for exactly that.
val nativeHostName = if (isWindowsHost) "adfree-native-host.exe" else "adfree-native-host"
/**
 * The native-messaging host is the other half of the browser bridge: AdGuard's
 * extension asks Chrome to start this binary, and without it the extension
 * answers "AdFree is not installed" on a machine that is filtering. A package
 * has no repository beside it, so the host travels in the jar the way the core
 * does — `NativeMessagingHosts.unpackedHostBinary` writes it out to the cache.
 */
val nativeHostBinary: File = listOf(
    "rust/target/release/$nativeHostName",
    "rust/target/x86_64-pc-windows-gnu/release/$nativeHostName",
).map { rootProject.layout.projectDirectory.file(it).asFile }.firstOrNull { it.isFile }
    ?: rootProject.layout.projectDirectory.file("rust/target/release/$nativeHostName").asFile

val stageCoreLibrary by tasks.registering(Copy::class) {
    description = "Puts the Rust core into the app jar, where every package carries it"
    from(coreLibrary)
    into(layout.buildDirectory.dir("coreResources"))
    rename { coreLibraryName }
}

/**
 * And the host beside it. Optional at build time, because `:desktop:runApp` is
 * run after `cargo build --release --lib` and an app without the browser bridge
 * is still a filtering app; the packaging check below is where a release states
 * that it refuses to ship without it.
 */
val stageNativeHost by tasks.registering(Copy::class) {
    description = "Puts the native-messaging host into the app jar"
    onlyIf { nativeHostBinary.isFile }
    from(nativeHostBinary)
    into(layout.buildDirectory.dir("coreResources"))
    rename { nativeHostName }
}

sourceSets["main"].resources.srcDir(layout.buildDirectory.dir("coreResources"))
tasks.named("processResources") { dependsOn(stageCoreLibrary, stageNativeHost) }

// A class archive needs a base archive to be written from, and jpackage's
// runtime image ships `libjvm` without one: every attempt at a dynamic archive
// in such an image answers `-XX:ArchiveClassesAtExit is unsupported when base
// CDS archive is not loaded`. The base archive is a property of the image's own
// `lib/modules` — the one beside the build JDK does not fit, because jlink
// prunes the module set — so it is dumped here, by the image's own JVM.
//
// The image carries no `java` launcher to dump with (jpackage strips it), so
// the build stages one from the JDK that made the image, dumps, and removes it
// again. What remains is `lib/server/classes.jsa`: JDK classes already resolved,
// which every launch of the packaged app maps instead of reading.
val hostJavaHome = File(System.getProperty("java.home"))
val javaExecutableName = if (isWindowsHost) "java.exe" else "java"
// `createRuntimeImage` is the image jpackage is handed, and `createDistributable`
// is the same image laid out for a dev run; both are dumped so a packaged
// installer and a local run start from the same classes.
tasks.matching { it.name == "createRuntimeImage" || it.name == "createDistributable" }.configureEach {
    doLast {
        val roots = outputs.files.files.filter { it.exists() }
        val libjvm =
            roots
                .asSequence()
                .flatMap { root -> root.walkTopDown() }
                .firstOrNull { it.isFile && it.name.startsWith("libjvm.") }
        if (libjvm == null) {
            logger.lifecycle("cds: no libjvm under ${roots.joinToString { it.absolutePath }}")
            return@doLast
        }
        // `<runtime>/lib/server/libjvm.so`: the runtime root is two levels up.
        val runtimeLib = libjvm.parentFile.parentFile
        val runtimeRoot = runtimeLib.parentFile
        val staged = File(runtimeRoot, "bin")
        val stagedNow = mutableListOf<File>()
        val dumpLog = File(temporaryDir, "cds-dump.log")
        val failure =
            runCatching {
                // `java` is not the only file it needs: the launcher loads the
                // rest of `bin` beside it, and which of them a platform wants is
                // the platform's business.
                File(hostJavaHome, "bin").listFiles().orEmpty()
                    .filter { it.isFile && it.length() < 8_000_000 }
                    .forEach { source ->
                        val target = File(staged, source.name)
                        if (!target.exists()) {
                            staged.mkdirs()
                            source.copyTo(target, overwrite = true)
                            target.setExecutable(true)
                            stagedNow += target
                        }
                    }
                val dump =
                    ProcessBuilder(File(staged, javaExecutableName).absolutePath, "-Xshare:dump")
                        .directory(runtimeRoot)
                        .redirectErrorStream(true)
                        .redirectOutput(dumpLog)
                        .start()
                val code = dump.waitFor()
                check(code == 0) { "the class archive dump exited $code" }
                val archive = File(runtimeLib, "server/classes.jsa")
                check(archive.isFile) { "the dump wrote no archive" }
                logger.lifecycle("cds: base class archive -> ${archive.absolutePath} (${archive.length()} bytes)")
            }
        stagedNow.forEach { it.delete() }
        if (staged.listFiles().isNullOrEmpty()) staged.delete()
        failure.exceptionOrNull()?.let { cause ->
            logger.warn("cds: no base class archive was written: ${cause.message}")
            dumpLog.takeIf { it.isFile }?.readLines()?.takeLast(8)?.forEach { logger.warn("cds:   $it") }
        }
    }
}

compose.desktop {
    application {
        mainClass = "git.shin.adfree.desktop.AdFreeDesktopKt"
        // The core registers native upcalls; without this flag every one of
        // them is a warning on a JDK that has started enforcing it.
        jvmArgs += listOf("--enable-native-access=ALL-UNNAMED")
        // Windows only, because Windows is the one install directory the
        // process owns and can write: the archive is written where the app
        // lives. `AutoCreateSharedArchive` maps `adfree.jsa` when it is there
        // and writes it on the first clean exit when it is not, so an update —
        // whose jars no longer match the archive — replaces it by itself. A
        // Linux install under /opt belongs to root and would only inherit the
        // warning on every launch.
        if (isWindowsHost) {
            jvmArgs +=
                listOf(
                    "-XX:SharedArchiveFile=\$APPDIR/adfree.jsa",
                    "-XX:+AutoCreateSharedArchive",
                )
        }

        nativeDistributions {
            // The whole JDK, because a hand-listed runtime fails silently and
            // only here: the plugin's default image is four modules, and the
            // packaged app needed `java.net.http` for its update screen and
            // `jdk.security.auth` for the tray's uid — each one an exception at
            // startup that a dev run on a full JDK cannot reproduce. A list has
            // to be right about every future use; this cannot be wrong. The
            // image is bigger by tens of megabytes, once per install.
            includeAllModules = true
            // `versionName` is what the release tags carry; jpackage takes only
            // digits and dots, so the phone build's version name is what both
            // halves of the release are named after.
            val releaseVersion = (rootProject.findProperty("versionName") as? String)
                ?.takeUnless { it.isBlank() } ?: "1.0.0"
            packageName = "adfree"
            packageVersion = releaseVersion.substringBefore("-")
            description = "System-wide ad blocking: a transparent proxy, a DNS server and a TLS bridge."
            vendor = "Tachibana Shin"
            copyright = "© Tachibana Shin"
            // One format per family: a deb/rpm for Linux distributions, an MSI
            // for Windows. jpackage only builds for the host it runs on, so the
            // list is chosen there rather than filtered afterwards.
            if (isWindowsHost) {
                targetFormats(TargetFormat.Msi)
                windows {
                    // WiX takes MAJOR.MINOR.BUILD with hard ranges (255, 255,
                    // 65535) and refuses anything else, so the release name is
                    // reduced to exactly that instead of being passed through:
                    // "1.4.0-beta" and "2" are both real version names, and only
                    // one of them is a legal MSI version.
                    val parts = releaseVersion
                        .substringBefore("-")
                        .split(".")
                        .map { it.toIntOrNull() ?: 0 }
                    msiPackageVersion = listOf(
                        parts.getOrElse(0) { 1 }.coerceIn(0, 255),
                        parts.getOrElse(1) { 0 }.coerceIn(0, 255),
                        parts.getOrElse(2) { 0 }.coerceIn(0, 65535),
                    ).joinToString(".")
                    // Stable across releases: MSI upgrades match on this, and a
                    // new one turns an upgrade into a second installed copy.
                    upgradeUuid = "8f2b1c74-4a5e-4c1b-9f39-2d0a7b6c1e55"
                    menuGroup = "AdFree"
                    shortcut = true
                    // Consoles: the app needs no window of its own, and a
                    // package that asks for one opens a black box at logon.
                    console = false
                    // The driver and the certificate store are machine-wide and
                    // need the administrator token, which a per-user MSI cannot
                    // grant. Installing for all users matches what the app
                    // itself must already be.
                    perUserInstall = false
                    // Without this jpackage falls back to its own default and
                    // the installed app wears the Kotlin mark on the exe, the
                    // Start menu shortcut and the taskbar group: the icon the
                    // window carries is only the window's.
                    iconFile.set(project.file("src/main/resources/icon/adfree.ico"))
                }
            } else {
                targetFormats(TargetFormat.Deb, TargetFormat.Rpm)
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon/adfree.png"))
                menuGroup = "Network"
                appCategory = "Network"
                debMaintainer = "Tachibana Shin <tachibana-shin@users.noreply.github.com>"
                rpmLicenseType = "Apache-2.0"
                debPackageVersion = releaseVersion
            }
        }
    }
}

// A package without the core is a window that cannot filter, and every step on
// the way treats that as normal, so the build says so instead.
tasks.matching {
    it.name in setOf(
        "createDistributable",
        "createReleaseDistributable",
        "packageDistributionForCurrentOS",
        "packageReleaseDistributionForCurrentOS",
        "packageDeb",
        "packageReleaseDeb",
        "packageRpm",
        "packageReleaseRpm",
        "packageMsi",
        "packageReleaseMsi",
    )
}.configureEach {
    doFirst {
        if (!coreLibrary.isFile) {
            throw GradleException(
                "no ${coreLibrary.path} to package; run `cargo build --release --lib` in rust/ first",
            )
        }
        // AdGuard's browser extension answers "not installed" without it, and
        // that answer is what a user reads as the app being broken.
        if (!nativeHostBinary.isFile) {
            throw GradleException(
                "no ${nativeHostBinary.path} to package; run " +
                    "`cargo build --release --bin adfree-native-host` in rust/ first",
            )
        }
    }
}

// An MSI that cannot find WinDivert is an app that starts and cannot filter. The
// files travel in the jar (``WinDivertFiles``), so the check is that the jar's
// resources are there before packaging, not that they are staged beside it.
tasks.matching { it.name.startsWith("package") && it.name.contains("Msi") }.configureEach {
    doFirst {
        val resource = project.file("src/main/resources/windivert/x64/WinDivert.dll")
        if (isWindowsHost && !resource.isFile) {
            throw GradleException("missing ${resource.path}; the MSI would ship without the driver")
        }
    }
}