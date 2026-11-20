package git.shin.adfree.data

import git.shin.adfree.core.OverrideRule
import git.shin.adfree.core.coreCaptureSetOverrides
import git.shin.adfree.platform.KeyValueStore

/**
 * An answer the reader wrote in place of the origin's.
 *
 * The inspector shows what the wire said; a replacement is what the reader says
 * instead — a status, some headers and a body — matched to requests by a
 * substring of the URL. It applies only while the inspector window is open, and
 * is saved so the next open has it again.
 */
data class ResponseOverride(
    val pattern: String = "",
    val method: String = "",
    val status: String = "200",
    val headers: String = "",
    val body: String = "",
    /** The body is base64 and stands for bytes no editor could hold. */
    val binary: Boolean = false,
) {
    /** The status as a number, or 200 when it is not one yet. */
    fun statusCode(): Int = status.trim().toIntOrNull()?.coerceIn(100, 599) ?: 200

    /** The headers as the lines the store keeps, one `Name: value` each. */
    fun headerLines(): List<String> =
        headers
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Whether this replacement can match anything.
     *
     * An empty pattern matches nothing on purpose: a row the reader is still
     * writing must not answer every request in the meantime.
     */
    fun usable(): Boolean = pattern.isNotBlank()

    fun toRule(): OverrideRule =
        OverrideRule(
            pattern = pattern.trim(),
            method =
                method
                    .trim()
                    .uppercase()
                    .takeIf { it != "ANY" }
                    .orEmpty(),
            status = statusCode().toUInt().toInt(),
            headers = headerLines(),
            body = body,
            bodyBase64 = binary,
        )

    /**
     * The text form, one replacement per block.
     *
     * Every line carries the number of the field it belongs to, so a body may
     * hold anything at all — a line that looks like a header, a blank line, a
     * copy of this format — and still read back exactly as it was written.
     */
    fun encode(): String =
        buildString {
            appendLine("1 " + pattern.replace("\n", " "))
            appendLine("2 " + method.replace("\n", " "))
            appendLine("3 " + status.replace("\n", " "))
            if (binary) appendLine("6 base64")
            headerLines().forEach { line -> appendLine("4 $line") }
            body.split("\n").forEach { line -> appendLine("5 $line") }
        }

    companion object {
        /** Reads back what [encode] wrote; anything else is not a block. */
        fun decode(text: String): List<ResponseOverride> {
            val out = mutableListOf<ResponseOverride>()
            val headers = mutableListOf<String>()
            val body = mutableListOf<String>()
            var current: ResponseOverride? = null
            var binary = false

            fun flush() {
                val done = current ?: return
                out +=
                    done.copy(
                        headers = headers.joinToString("\n"),
                        body = body.joinToString("\n"),
                        binary = binary,
                    )
                binary = false
                headers.clear()
                body.clear()
            }
            text.split('\n').forEach { line ->
                val field = line.take(1)
                val value = line.drop(2)
                when (field) {
                    "1" -> {
                        flush()
                        current = ResponseOverride(pattern = value)
                    }

                    "2" -> {
                        current = current?.copy(method = value)
                    }

                    "3" -> {
                        current = current?.copy(status = value.ifEmpty { "200" })
                    }

                    // The body's kind is written before the headers, so a
                    // header that reads like this format cannot set it.
                    "6" -> {
                        binary = value == "base64"
                    }

                    "4" -> {
                        if (current != null) headers += value
                    }

                    "5" -> {
                        if (current != null) body += value
                    }
                }
            }
            flush()
            return out
        }
    }
}

/**
 * The bytes a base64 body names, or `None` when the text is not base64.
 *
 * The core decodes the same text when the rule is handed over; this copy is for
 * the preview, which has to show the reader the bytes they are about to stand
 * behind before they stand behind them.
 */
fun base64Bytes(text: String): ByteArray? {
    val out = java.io.ByteArrayOutputStream(text.length / 4 * 3)
    var acc = 0
    var bits = 0
    for (c in text) {
        if (c.isWhitespace()) continue
        if (c == '=') break
        val value =
            when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return null
            }
        acc = (acc shl 6) or value
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out.write((acc shr bits) and 0xFF)
        }
    }
    return out.toByteArray()
}

/**
 * The replacements the reader wrote, kept where the pane's own position is
 * kept, and handed to the core while the window that applies them is open.
 */
class OverrideStore(
    private val store: KeyValueStore,
) {
    fun load(): List<ResponseOverride> = ResponseOverride.decode(store.getString(KEY).orEmpty())

    fun save(rules: List<ResponseOverride>) {
        store.putString(KEY, rules.joinToString("\n") { it.encode() })
    }

    /**
     * Hands the core the set it should apply.
     *
     * Called on open with what was saved and on close with nothing, because a
     * replacement is in force while the window that wrote it exists: a rule
     * that outlived its window would be a change to a page nobody asked to
     * change any more.
     */
    fun apply(rules: List<ResponseOverride>) {
        coreCaptureSetOverrides(
            rules
                .filter { it.usable() }
                .map { it.toRule() },
        )
    }

    private companion object {
        const val KEY = "inspector.overrides"
    }
}
