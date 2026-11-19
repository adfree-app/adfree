package git.shin.adfree.model

/**
 * One captured request, in the shapes somebody actually pastes.
 *
 * A tracker's most common errand is "run that again by hand", and the three
 * places it gets pasted into quote differently: a browser console takes a
 * `fetch` call, a POSIX shell takes single quotes, and `cmd.exe` takes double
 * ones and `^` continuations. Building the strings here keeps them out of the
 * screen and makes them testable.
 */
object RequestCopy {
    /**
     * Headers a copied command must not repeat: the first two are recomputed by
     * the client, the next three belong to one connection and would corrupt a
     * new one, and `accept-encoding` is dropped because a terminal cannot read
     * a brotli body — the browser's own "copy as cURL" drops it for the same
     * reason.
     */
    private val SKIP =
        setOf(
            "host",
            "content-length",
            "connection",
            "proxy-connection",
            "transfer-encoding",
            "accept-encoding",
        )

    /** The method, the target and the headers of a request head. */
    data class Line(
        val method: String,
        val target: String,
        val headers: List<Pair<String, String>>,
    )

    fun parse(head: String): Line {
        val lines = head.lines()
        val first = lines.firstOrNull().orEmpty().split(' ')
        val method = first.getOrNull(0)?.takeIf { it.isNotBlank() } ?: "GET"
        val target = first.getOrNull(1)?.takeIf { it.isNotBlank() } ?: "/"
        val headers = lines.drop(1).mapNotNull { line ->
            if (line.isBlank()) {
                null
            } else {
                val colon = line.indexOf(':')
                if (colon <= 0) {
                    null
                } else {
                    val name = line.substring(0, colon).trim()
                    if (name.lowercase() in SKIP) name to "" else name to line.substring(colon + 1).trim()
                }
            }
        }.filter { it.second.isNotEmpty() }
        return Line(method, target, headers)
    }

    /** The request as a console call, the way a browser writes one. */
    fun fetch(
        url: String,
        head: String,
    ): String {
        val line = parse(head)
        val headers =
            line.headers.joinToString(",\n") { (name, value) ->
                "    ${quote(name, '"')}: ${quote(value, '"')}"
            }
        val body = if (headers.isEmpty()) "" else ",\n  headers: {\n$headers,\n  }"
        return "await fetch(${quote(url, '"')}, {\n  method: ${quote(line.method, '"')}$body,\n});\n"
    }

    /** The request as a shell command, quoted for a POSIX shell. */
    fun curlBash(
        url: String,
        head: String,
    ): String {
        val line = parse(head)
        val parts = mutableListOf("curl ${quote(url, '\'')}")
        if (line.method.uppercase() != "GET") parts += "-X ${line.method}"
        line.headers.forEach { (name, value) -> parts += "-H ${quote("$name: $value", '\'')}" }
        return parts.joinToString(" \\\n  ") + "\n"
    }

    /** The same command for `cmd.exe`, which has no single quotes to lend. */
    fun curlCmd(
        url: String,
        head: String,
    ): String {
        val line = parse(head)
        val parts = mutableListOf("curl ${quote(url, '"')}")
        if (line.method.uppercase() != "GET") parts += "-X ${line.method}"
        line.headers.forEach { (name, value) -> parts += "-H ${quote("$name: $value", '"')}" }
        return parts.joinToString(" ^\n  ") + "\n"
    }

    /**
     * Wraps a value in [mark] and escapes what would end it: a backslash and the
     * mark itself. `cmd.exe` and a POSIX shell both treat a lone `'` or `"` as
     * the end of the run, and a cookie or a header value is exactly the kind of
     * text that carries one.
     */
    private fun quote(
        value: String,
        mark: Char,
    ): String {
        val escaped = value.replace("\\", "\\\\").replace(mark.toString(), "\\$mark")
        return "$mark$escaped$mark"
    }
}
