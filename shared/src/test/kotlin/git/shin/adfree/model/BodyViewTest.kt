package git.shin.adfree.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyViewTest {
    @Test
    fun theLabelDecidesWhenItNamesSomething() {
        assertEquals(BodyKind.JSON, BodyFormats.of("application/json", "{}"))
        assertEquals(BodyKind.CSS, BodyFormats.of("text/css", "a{}"))
        assertEquals(BodyKind.SSE, BodyFormats.of("text/event-stream", "data: 1\n\n"))
        assertEquals(BodyKind.IMAGE, BodyFormats.of("image/png", "\uFFFDPNG"))
        assertEquals(BodyKind.FONT, BodyFormats.of("font/woff2", "wOF2"))
    }

    @Test
    fun aVagueLabelLetsTheBytesSpeak() {
        // Next.js answers JSON and RSC as octet-stream and text/plain.
        assertEquals(BodyKind.JSON, BodyFormats.of("application/octet-stream", """{"a":1}"""))
        assertEquals(BodyKind.HTML, BodyFormats.of("text/plain", "<!DOCTYPE html><p>x"))
        assertEquals(BodyKind.SSE, BodyFormats.of("text/plain", "data: hello\n\n"))
    }

    @Test
    fun aContentTypeWithParametersKeepsItsType() {
        assertEquals(
            "application/json",
            BodyFormats.mediaType("Content-Type: application/json; charset=utf-8\r\n"),
        )
        assertEquals("text/event-stream", BodyFormats.mediaType("content-type: text/event-stream\r\n"))
    }

    @Test
    fun aJsonKeyIsAKeyAndNotJustAString() {
        val text = """{"name":"a","n":12,"ok":true}"""
        val spans = BodySyntax.spans(BodyKind.JSON, text)
        val keys = spans.filter { it.tone == Tone.KEY }.map { text.substring(it.start, it.end) }
        assertTrue("keys were $keys", keys.any { it.startsWith("\"name\"") })
        assertTrue(spans.any { it.tone == Tone.NUMBER })
        assertTrue(spans.any { it.tone == Tone.LITERAL })
    }

    @Test
    fun theBracesOfAJsonBodyAreNotLeftUncoloured() {
        val text = """{"a":1}"""
        val spans = BodySyntax.spans(BodyKind.JSON, text)
        val painted = spans.flatMap { it.start until it.end }.toSet()
        assertTrue("braces were unclaimed", 0 in painted && text.lastIndex in painted)
    }

    @Test
    fun noTwoSpansPaintTheSameCharacter() {
        val text = """{"name":"a","n":12}"""
        val spans = BodySyntax.spans(BodyKind.JSON, text)
        val painted = mutableSetOf<Int>()
        spans.forEach { span ->
            for (index in span.start until span.end) {
                assertTrue("index $index painted twice", painted.add(index))
            }
        }
    }

    @Test
    fun htmlTagsAndAttributesAreToldApart() {
        val spans = BodySyntax.spans(BodyKind.HTML, """<div class="x">hi</div>""")
        assertTrue(spans.any { it.tone == Tone.TAG })
        assertTrue(spans.any { it.tone == Tone.ATTR })
        assertTrue(spans.any { it.tone == Tone.STRING })
    }

    @Test
    fun anEventStreamsFieldsAreMarked() {
        val spans = BodySyntax.spans(BodyKind.SSE, "event: ping\ndata: {\"a\":1}\n\n")
        assertTrue(spans.any { it.tone == Tone.KEY })
    }

    @Test
    fun jsonIsPutOnItsOwnLines() {
        assertEquals(
            """
            {
              "a": 1,
              "b": [
                1,
                2
              ]
            }
            """.trimIndent(),
            JsonText.pretty("""{"a":1,"b":[1,2]}"""),
        )
    }

    @Test
    fun aStringOfBracesDoesNotIndent() {
        assertEquals(
            """
            {
              "a": "{[ not structure ]}"
            }
            """.trimIndent(),
            JsonText.pretty("""{"a":"{[ not structure ]}"}"""),
        )
    }

    @Test
    fun aTruncatedPreviewIsNotFormattedAsIfItWereWhole() {
        assertNull(JsonText.pretty("""{"a":1,"b":{"c":"tru"""))
    }

    @Test
    fun theHexViewerShowsOffsetsBytesAndText() {
        val rows = HexView.rows("Hello, world!".encodeToByteArray(), perRow = 8)
        assertEquals(2, rows.size)
        assertEquals(0, rows[0].offset)
        assertEquals("48 65 6c 6c 6f 2c 20 77", rows[0].hex)
        assertEquals("Hello, w", rows[0].ascii)
        assertEquals(8, rows[1].offset)
        assertEquals("orld!", rows[1].ascii.replace(".", ""))
    }

    @Test
    fun aByteThatWasNeverTextIsNotShownAsThreeBytes() {
        val rows = HexView.rowsOfPreview("\uFFFD")
        assertEquals(1, rows.size)
        assertEquals("-- --", rows[0].hex)
        assertEquals("..", rows[0].ascii)
    }

    @Test
    fun anEventStreamIsCutIntoItsEvents() {
        val events = SseText.events("event: ping\ndata: {\"a\":1}\n\ndata: two\ndata: lines\n\n")
        assertEquals(2, events.size)
        assertEquals("ping", events[0].event)
        assertEquals("""{"a":1}""", events[0].data)
        assertEquals("message", events[1].event)
        assertEquals("two\nlines", events[1].data)
        assertEquals("two lines", events[1].preview)
    }

    @Test
    fun aCommentIsNotAnEvent() {
        assertTrue(SseText.events(": keep-alive\n\n").isEmpty())
    }

    @Test
    fun theTextPreviewIsShownAsItsUtf8Bytes() {
        val rows = HexView.rowsOfPreview("héllo", perRow = 16)
        assertEquals("68 c3 a9 6c 6c 6f", rows[0].hex)
    }
}
