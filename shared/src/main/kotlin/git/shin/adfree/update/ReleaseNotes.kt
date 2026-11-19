package git.shin.adfree.update

/**
 * A release body is Markdown, written by semantic-release, and a `Text` that
 * shows it raw shows the syntax instead of the changelog: `### Features`,
 * `**scope:**`, `* a commit`. This turns it into the lines a screen can draw.
 *
 * Not a Markdown parser: the shape a changelog actually has — headings,
 * bullets, code spans and links — is stripped of its markers, and anything
 * else is left as the words it already is. Rendering the real thing would mean
 * shipping a parser for a page of text that has three kinds of line.
 */
object ReleaseNotes {
    enum class Kind { HEADING, BULLET, TEXT }

    /** One line, and what it is for the screen that draws it. */
    data class Line(
        val kind: Kind,
        val text: String,
    )

    private val HEADING = Regex("""^#{1,6}\s+(.*)$""")
    private val BULLET = Regex("""^\s*[*-]\s+(.*)$""")
    private val LINK = Regex("""\[([^\]]+)]\(([^)]+)\)""")
    private val CODE = Regex("""`([^`]+)`""")
    private val BOLD = Regex("""(\*\*|__)(.+?)\1""")
    private val HTML_COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    fun lines(body: String): List<Line> =
        body
            .replace(HTML_COMMENT, "")
            .lines()
            .mapNotNull { raw ->
                val line = raw.trimEnd()
                when {
                    line.isBlank() -> {
                        null
                    }

                    // A horizontal rule is a separator with nothing to say.
                    line.trim().all { it == '-' || it == '*' || it == '_' } -> {
                        null
                    }

                    line.startsWith("```") -> {
                        null
                    }

                    HEADING.matches(line) -> {
                        Line(Kind.HEADING, inline(HEADING.replace(line, "$1").trim()))
                    }

                    BULLET.matches(line) -> {
                        Line(Kind.BULLET, inline(BULLET.replace(line, "$1").trim()))
                    }

                    else -> {
                        Line(Kind.TEXT, inline(line.trim()))
                    }
                }
            }

    /** Strips the marks that only mean something to a renderer. */
    fun inline(text: String): String =
        text
            .replace(LINK, "$1")
            .replace(CODE, "$1")
            .replace(BOLD, "$2")
}
