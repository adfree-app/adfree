package git.shin.adfree.update

/**
 * The one place the app learns that a newer build exists.
 *
 * Both updaters read this: Android checks the feed and installs the signed APK
 * beside it, the desktop checks the same feed and hands the release page to the
 * browser, because a packaged desktop build is replaced by a package manager,
 * not by an installer a running app can run.
 *
 * The pair lives here rather than in each platform because the repository has
 * moved once already, and a moved repository that only one of them knows about
 * is one platform that quietly stops updating.
 */
object ReleaseFeed {
    const val LATEST_RELEASE_API = "https://api.github.com/repos/adfree-app/adfree/releases/latest"

    const val RELEASES_PAGE = "https://github.com/adfree-app/adfree/releases"

    /** Tags are `v1.18.0`; the running version is `1.18.0`. */
    const val TAG_PREFIX = "v"

    /** Compares two dotted versions, ignoring a leading `v`. Returns <0, 0, >0. */
    fun compare(
        left: String,
        right: String,
    ): Int {
        val a = numbers(left)
        val b = numbers(right)
        val size = maxOf(a.size, b.size)
        for (index in 0 until size) {
            val difference = (a.getOrNull(index) ?: 0) - (b.getOrNull(index) ?: 0)
            if (difference != 0) return difference
        }
        return 0
    }

    /** The release feed says `v1.18.0`; the UI says `1.18.0`. */
    fun versionOf(tag: String): String = tag.removePrefix(TAG_PREFIX).trim()

    private fun numbers(version: String): List<Int> =
        version
            .removePrefix(TAG_PREFIX)
            .substringBefore('-')
            .split('.')
            .mapNotNull { it.trim().takeWhile(Char::isDigit).toIntOrNull() }
}
