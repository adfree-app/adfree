package git.shin.adfree.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseOverridesTest {
    @Test
    fun aReplacementSurvivesTheStore() {
        val rules =
            listOf(
                ResponseOverride(
                    pattern = "example.com/api/user",
                    method = "post",
                    status = "418",
                    headers = "content-type: application/json\nx-made-by: me",
                    body = "{\"name\":\"x\"}",
                ),
                ResponseOverride(pattern = "ads.js", status = "404", body = ""),
            )
        val back = ResponseOverride.decode(rules.joinToString("\n") { it.encode() })
        assertEquals(2, back.size)
        assertEquals("example.com/api/user", back[0].pattern)
        assertEquals("POST", back[0].toRule().method)
        assertEquals(418, back[0].statusCode())
        assertEquals(listOf("content-type: application/json", "x-made-by: me"), back[0].headerLines())
        assertEquals("{\"name\":\"x\"}", back[0].body)
        assertEquals("ads.js", back[1].pattern)
        assertEquals("", back[1].body)
    }

    @Test
    fun aBodyThatLooksLikeTheFormatReadsBackWhole() {
        val body = "1 not a new replacement\n5 so is this\n\nafter a blank line"
        val back = ResponseOverride.decode(ResponseOverride(pattern = "x", body = body).encode())
        assertEquals(1, back.size)
        assertEquals("x", back[0].pattern)
        assertEquals(body, back[0].body)
    }

    @Test
    fun aBinaryBodyIsSavedAsBinary() {
        val rule = ResponseOverride(pattern = "logo.png", status = "200", body = "iVBORw0KGgo=", binary = true)
        val back = ResponseOverride.decode(rule.encode())[0]
        assertTrue(back.binary)
        assertEquals("iVBORw0KGgo=", back.body)
        assertTrue(back.toRule().bodyBase64)
        // And a text body stays text.
        val text = ResponseOverride.decode(ResponseOverride(pattern = "x", body = "hi").encode())[0]
        assertFalse(text.binary)
        assertFalse(text.toRule().bodyBase64)
    }

    @Test
    fun aHeaderLineIsNotMistakenForTheStatus() {
        val rule = ResponseOverride(pattern = "x", headers = "200: not a status")
        val back = ResponseOverride.decode(rule.encode())[0]
        assertEquals("200", back.status)
        assertEquals(listOf("200: not a status"), back.headerLines())
    }

    @Test
    fun anEmptyPatternIsNotHandedToTheCore() {
        assertFalse(ResponseOverride(pattern = "  ").usable())
        assertTrue(ResponseOverride(pattern = "x").usable())
    }

    @Test
    fun aStatusThatIsNotANumberFallsBackTo200() {
        assertEquals(200, ResponseOverride(pattern = "x", status = "abc").statusCode())
        // Out of range is clamped rather than refused: a typo in the box must
        // not produce a status line no client can parse.
        assertEquals(599, ResponseOverride(pattern = "x", status = "900").statusCode())
    }
}
