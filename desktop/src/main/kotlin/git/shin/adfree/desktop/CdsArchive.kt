package git.shin.adfree.desktop

/**
 * The class archive a launch starts from, instead of a thousand jar entries.
 *
 * A cold start on the Windows box spent most of its first ten seconds inside
 * `Inflater.inflateBytesBytes`: every class the first composition touches has
 * to be found in a jar, seeked to, decompressed and verified, and there are
 * thousands of them. A class-data-sharing archive is those classes already
 * loaded, written out once and mapped back on the next launch, which is the one
 * thing here that attacks the cost at its source.
 *
 * Who writes it: the launcher itself. The Windows launcher carries
 * `-XX:SharedArchiveFile=$APPDIR/adfree.jsa` with `-XX:+AutoCreateSharedArchive`,
 * so the JVM maps the archive when it is there and writes it on the first clean
 * exit when it is not — including after an update, when the archive no longer
 * matches the jars. Nothing in this process has to arrange that, and nothing in
 * it can: a jpackage image ships no `bin/java` to run a training pass with.
 *
 * [TRAIN_ARG] stays because a clean exit is not something a test can otherwise
 * cause on a machine with a tray icon — the window closing only hides it.
 */
internal object CdsArchive {
    /** Composes the window once and exits, so the JVM writes the archive. */
    const val TRAIN_ARG = "--cds-train"

    const val FILE_NAME = "adfree.jsa"
}
