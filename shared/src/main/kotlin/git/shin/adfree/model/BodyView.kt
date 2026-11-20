package git.shin.adfree.model

import kotlin.math.min

/**
 * What a body is, and how it should be read.
 *
 * The tracker keeps a lossy preview of the first bytes of each side, so the
 * only witnesses are the `Content-Type` and the bytes themselves. A server that
 * labels itself `application/json` and sends HTML is common enough that the
 * label is checked first and then questioned: the sniffer only speaks when the
 * label says nothing useful.
 */
enum class BodyKind {
    JSON,
    XML,
    HTML,
    CSS,
    JAVASCRIPT,
    YAML,
    SSE,
    FORM,
    IMAGE,
    MEDIA,
    FONT,
    TEXT,
    BINARY,
}

object BodyFormats {
    /** The media type without its parameters, lowercased. */
    fun mediaType(head: String): String =
        head
            .lineSequence()
            .firstOrNull { it.startsWith("content-type:", ignoreCase = true) }
            .orEmpty()
            .substringAfter(':')
            .substringBefore(';')
            .trim()
            .lowercase()

    fun of(
        mediaType: String,
        body: String,
    ): BodyKind {
        val type = mediaType.lowercase()
        mediaTypeKind(type)?.let { labeled ->
            // A document that is plainly something else wins over a vague
            // label; `octet-stream` and `text/plain` are the vague ones.
            val vague = type.isEmpty() || "octet-stream" in type || "text/plain" in type
            if (!vague) return labeled
            return sniff(body) ?: labeled
        }
        return sniff(body) ?: BodyKind.TEXT
    }

    private fun mediaTypeKind(type: String): BodyKind? =
        when {
            type.isEmpty() -> null
            "json" in type -> BodyKind.JSON
            "html" in type -> BodyKind.HTML
            "xml" in type -> BodyKind.XML
            "css" in type -> BodyKind.CSS
            "javascript" in type || "ecmascript" in type -> BodyKind.JAVASCRIPT
            "yaml" in type -> BodyKind.YAML
            "event-stream" in type -> BodyKind.SSE
            "x-www-form-urlencoded" in type -> BodyKind.FORM
            type.startsWith("image/") -> BodyKind.IMAGE
            type.startsWith("audio/") || type.startsWith("video/") -> BodyKind.MEDIA
            type.startsWith("font/") || "font" in type -> BodyKind.FONT
            type.startsWith("text/") -> BodyKind.TEXT
            else -> BodyKind.BINARY
        }

    /** What the bytes look like, when the label did not say. */
    fun sniff(body: String): BodyKind? {
        val text = body.trimStart()
        if (text.isEmpty()) return null
        return when {
            text.startsWith("{") || text.startsWith("[") -> BodyKind.JSON
            text.startsWith("<!doctype", true) || text.startsWith("<html", true) -> BodyKind.HTML
            text.startsWith("<?xml", true) -> BodyKind.XML
            // An event stream is blocks separated by a blank line, each with at
            // least one `data:` line.
            text.startsWith("data:") || text.startsWith("event:") || text.startsWith(":\n") -> BodyKind.SSE
            text.startsWith("<") -> BodyKind.HTML
            '\u0000' in body.drop(0).take(2048) -> BodyKind.BINARY
            else -> null
        }
    }
}

/** One coloured stretch of a body. */
data class Span(val start: Int, val end: Int, val tone: Tone)

enum class Tone { KEY, STRING, NUMBER, LITERAL, COMMENT, TAG, ATTR, SELECTOR, PROPERTY }

/**
 * Enough of a tokenizer to make a body readable.
 *
 * Not a parser for each language: each one is scanned for the four or five
 * things that carry meaning — keys, strings, numbers, comments, tags — because
 * a preview is at most two kilobytes and a reader is looking for the one value
 * they came for.
 */
object BodySyntax {
    private val JSON_KEY = Regex(""""([^"\\]|\\.)*"\s*:""")
    private val STRING = Regex(""""([^"\\]|\\.)*"""")
    private val NUMBER = Regex("""-?\b\d+(\.\d+)?([eE][+-]?\d+)?\b""")
    private val LITERAL = Regex("""\b(true|false|null)\b""")
    private val TAG = Regex("""</?[A-Za-z][\w:-]*""")
    private val ATTR = Regex("""[\w:-]+=""")
    private val COMMENT = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
    private val BLOCK_COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val LINE_COMMENT = Regex("""//[^\n]*""")
    private val CSS_PROPERTY = Regex("""[-\w]+\s*:(?=\s)""")
    private val CSS_SELECTOR = Regex("""[^{};\n]+(?=\{)""")
    private val YAML_KEY = Regex("""^\s*[-\w.]+\s*:(?=\s|$)""", RegexOption.MULTILINE)
    private val SSE_FIELD = Regex("""^(data|event|id|retry):""", RegexOption.MULTILINE)
    private val WORD = Regex("""[A-Za-z_$][\w$]*""")
    private val JS_KEYWORD =
        Regex("""\b(function|const|let|var|return|if|else|for|while|new|class|import|export|await|async|null|true|false|undefined|this)\b""")

    /** Coloured spans, in order, with no overlaps. */
    fun spans(
        kind: BodyKind,
        text: String,
    ): List<Span> {
        val found = mutableListOf<Span>()
        fun add(regex: Regex, tone: Tone) {
            regex.findAll(text).forEach { match ->
                found += Span(match.range.first, match.range.last + 1, tone)
            }
        }
        when (kind) {
            BodyKind.JSON -> {
                add(STRING, Tone.STRING)
                add(NUMBER, Tone.NUMBER)
                add(LITERAL, Tone.LITERAL)
                // A key is a string followed by a colon, and it wins over the
                // string it also is.
                add(JSON_KEY, Tone.KEY)
            }
            BodyKind.XML,
            BodyKind.HTML,
            -> {
                add(COMMENT, Tone.COMMENT)
                add(TAG, Tone.TAG)
                add(STRING, Tone.STRING)
                add(ATTR, Tone.ATTR)
            }
            BodyKind.CSS -> {
                add(BLOCK_COMMENT, Tone.COMMENT)
                add(CSS_SELECTOR, Tone.SELECTOR)
                add(CSS_PROPERTY, Tone.PROPERTY)
                add(STRING, Tone.STRING)
                add(NUMBER, Tone.NUMBER)
            }
            BodyKind.JAVASCRIPT -> {
                add(BLOCK_COMMENT, Tone.COMMENT)
                add(LINE_COMMENT, Tone.COMMENT)
                add(STRING, Tone.STRING)
                add(JS_KEYWORD, Tone.LITERAL)
                add(NUMBER, Tone.NUMBER)
            }
            BodyKind.YAML -> {
                add(YAML_KEY, Tone.KEY)
                add(STRING, Tone.STRING)
                add(NUMBER, Tone.NUMBER)
                add(LITERAL, Tone.LITERAL)
            }
            BodyKind.SSE -> {
                add(SSE_FIELD, Tone.KEY)
                add(STRING, Tone.STRING)
                add(NUMBER, Tone.NUMBER)
            }
            BodyKind.FORM -> {
                add(Regex("""^[^=&\n]+="""), Tone.KEY)
                add(NUMBER, Tone.NUMBER)
            }
            BodyKind.TEXT,
            BodyKind.IMAGE,
            BodyKind.MEDIA,
            BodyKind.FONT,
            BodyKind.BINARY,
            -> Unit
        }
        return flatten(found, text.length)
    }

    /**
     * Sorts the overlaps out.
     *
     * Two rules matched the same characters (a JSON key is also a string, an
     * attribute name is also a word), and one span cannot be painted twice: the
     * later, more specific rule is kept for the characters it covers and the
     * earlier one is cut around it.
     */
    private fun flatten(
        spans: List<Span>,
        length: Int,
    ): List<Span> {
        val kept = mutableListOf<Span>()
        val taken = BooleanArray(length)
        spans.sortedByDescending { it.end - it.start }.forEach { span ->
            var start = span.start
            while (start < span.end && taken[start]) start++
            var end = span.end
            while (end > start && taken[end - 1]) end--
            if (end <= start) return@forEach
            for (index in start until end) taken[index] = true
            kept += Span(start, end, span.tone)
        }
        return kept.sortedBy { it.start }
    }
}

/**
 * JSON, laid out to be read.
 *
 * The inspector's raw view is the wire, which for a minified body is one long
 * line; this puts each key on its own line at its own depth. A body that is not
 * valid JSON comes back null, and the screen keeps the raw text instead of
 * showing an indentation conjectured from broken input.
 */
object JsonText {
    fun pretty(raw: String): String? {
        val out = StringBuilder(raw.length + raw.length / 4)
        var depth = 0
        var inString = false
        var escaped = false
        for (char in raw.trim()) {
            if (inString) {
                out.append(char)
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> inString = false
                }
                continue
            }
            when (char) {
                '"' -> {
                    inString = true
                    out.append(char)
                }
                '{', '[' -> {
                    depth++
                    out.append(char)
                    out.append('\n')
                    out.append("  ".repeat(depth))
                }
                '}', ']' -> {
                    depth--
                    out.append('\n')
                    out.append("  ".repeat(depth.coerceAtLeast(0)))
                    out.append(char)
                }
                ',' -> {
                    out.append(char)
                    out.append('\n')
                    out.append("  ".repeat(depth.coerceAtLeast(0)))
                }
                ':' -> out.append(": ")
                ' ', '\n', '\r', '\t' -> Unit
                else -> out.append(char)
            }
        }
        val text = out.toString()
        // Unbalanced input leaves a depth that never came back to zero, which
        // is how a truncated preview looks: it is not the document.
        if (depth != 0 || inString) return null
        return text
    }
}

/** A hex viewer, because a body that is not text still has to be looked at. */
object HexView {
    data class Row(val offset: Int, val hex: String, val ascii: String)

    fun rows(
        bytes: ByteArray,
        perRow: Int = 16,
    ): List<Row> {
        if (bytes.isEmpty()) return emptyList()
        return (0 until bytes.size step perRow).map { start ->
            val end = min(start + perRow, bytes.size)
            val hex = StringBuilder(perRow * 3)
            val ascii = StringBuilder(perRow)
            for (index in start until end) {
                val value = bytes[index].toInt() and 0xFF
                hex.append(HEX[value shr 4]).append(HEX[value and 0x0F]).append(' ')
                ascii.append(if (value in 0x20..0x7E) value.toChar() else '.')
            }
            Row(start, hex.toString().trimEnd(), ascii.toString())
        }
    }

    /**
     * The same, for the lossy text the tracker kept.
     *
     * The bytes are gone by the time the screen sees a record — the preview is
     * a `String` — so a replacement character stands for however many bytes
     * failed to decode, and it is shown as `--`: `EF BF BD` would be an
     * encoding artefact of the viewer pretending to be content.
     */
    fun rowsOfPreview(
        text: String,
        perRow: Int = 16,
    ): List<Row> {
        if (text.isEmpty()) return emptyList()
        val tokens =
            buildList {
                text.forEach { char ->
                    if (char == '\uFFFD') {
                        add("--")
                        add("--")
                    } else if (char.code < 0x80) {
                        add(char.code.toString(16).padStart(2, '0'))
                    } else {
                        char.toString().encodeToByteArray().forEach {
                            add((it.toInt() and 0xFF).toString(16).padStart(2, '0'))
                        }
                    }
                }
            }
        return (0 until tokens.size step perRow).map { start ->
            val end = min(start + perRow, tokens.size)
            val chunk = tokens.subList(start, end)
            Row(
                start,
                chunk.joinToString(" "),
                chunk.map { token ->
                    if (token == "--") '.' else token.toInt(16).toChar().takeIf { it.code in 0x20..0x7E } ?: '.'
                }.joinToString(""),
            )
        }
    }

    private const val HEX = "0123456789abcdef"
}

/**
 * An event stream, cut into the events it carries.
 *
 * A stream is blocks of `field: value` lines separated by a blank line, and the
 * interesting one is `data`. A page that streams for minutes would otherwise
 * read as one wall of text where a chunk cannot be told from the next; this
 * gives each block a number and its own row.
 */
object SseText {
    data class Event(
        val index: Int,
        val event: String,
        val data: String,
        val bytes: Int,
    ) {
        /** What the row shows: the payload on one line. */
        val preview: String
            get() = data.replace("\n", " ").trim()
    }

    fun events(text: String): List<Event> {
        val out = mutableListOf<Event>()
        for (block in text.split(Regex("\r?\n\r?\n"))) {
            var name = ""
            val data = StringBuilder()
            var sawField = false
            block.lineSequence().forEach { line ->
                if (line.isBlank() || line.startsWith(":")) return@forEach
                val field = line.substringBefore(':').trim()
                if (field !in FIELDS) return@forEach
                sawField = true
                val value = line.substringAfter(':', "").removePrefix(" ")
                if (field == "event") name = value
                if (field == "data") {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(value)
                }
            }
            if (!sawField) continue
            out += Event(out.size + 1, name.ifEmpty { "message" }, data.toString(), block.encodeToByteArray().size)
        }
        return out
    }

    private val FIELDS = setOf("data", "event", "id", "retry")
}
