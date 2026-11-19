package git.shin.adfree.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestCopyTest {
    private val head =
        "POST /v1/chat HTTP/1.1\r\n" +
            "Host: api.example.com\r\n" +
            "Accept: application/json\r\n" +
            "Accept-Encoding: gzip, br\r\n" +
            "Cookie: a=1\r\n" +
            "Content-Length: 12\r\n" +
            "Connection: keep-alive\r\n" +
            "\r\n"

    @Test
    fun theFirstLineIsTheMethodAndTheTarget() {
        val line = RequestCopy.parse(head)
        assertEquals("POST", line.method)
        assertEquals("/v1/chat", line.target)
    }

    @Test
    fun theHeadersANewConnectionWouldRebuildAreNotCopied() {
        val names = RequestCopy.parse(head).headers.map { it.first.lowercase() }
        assertFalse("host" in names)
        assertFalse("content-length" in names)
        assertFalse("connection" in names)
        assertTrue("accept-encoding" !in names)
        assertEquals(listOf("accept", "cookie"), names)
    }

    @Test
    fun aFetchCallCarriesTheMethodAndTheHeadersItMayRepeat() {
        val body = RequestCopy.fetch("https://api.example.com/v1/chat", head)
        assertTrue(body.startsWith("await fetch(\"https://api.example.com/v1/chat\", {"))
        assertTrue("method: \"POST\"" in body)
        assertTrue("\"Accept\": \"application/json\"" in body)
        assertTrue("\"Cookie\": \"a=1\"" in body)
        assertFalse("br" in body)
    }

    @Test
    fun aGetIsNotToldToUseGet() {
        val get = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"
        val command = RequestCopy.curlBash("https://example.com/", get)
        assertTrue(command.startsWith("curl 'https://example.com/'"))
        assertFalse("-X" in command)
    }

    @Test
    fun theTwoShellsDifferOnlyInTheMarkTheyQuoteWith() {
        val bash = RequestCopy.curlBash("https://example.com/", head)
        val cmd = RequestCopy.curlCmd("https://example.com/", head)
        assertEquals("curl 'https://example.com/' \\\n  -X POST \\\n  -H 'Accept: application/json' \\\n  -H 'Cookie: a=1'\n", bash)
        assertEquals("curl \"https://example.com/\" ^\n  -X POST ^\n  -H \"Accept: application/json\" ^\n  -H \"Cookie: a=1\"\n", cmd)
    }

    @Test
    fun aQuoteInsideAValueCannotEndTheQuoting() {
        val tricky = "GET / HTTP/1.1\r\nX-Name: it's \"fine\"\r\n\r\n"
        assertTrue("'X-Name: it\\'s \"fine\"'" in RequestCopy.curlBash("https://e.com/", tricky))
        assertTrue("\"X-Name: it's \\\"fine\\\"\"" in RequestCopy.curlCmd("https://e.com/", tricky))
    }

    @Test
    fun powershellGetsAHashtableRatherThanTheCurlFlag() {
        val command = RequestCopy.powershell("https://example.com/v1/chat", head)
        assertEquals(
            "Invoke-WebRequest -Uri 'https://example.com/v1/chat' `\n" +
                "  -Method POST `\n" +
                "  -Headers @{ 'Accept' = 'application/json'; 'Cookie' = 'a=1' }\n",
            command,
        )
    }

    @Test
    fun aPowerShellStringEscapesWithADoubledQuote() {
        val tricky = "GET / HTTP/1.1\r\nX-Name: it's fine\r\n\r\n"
        assertTrue("'X-Name' = 'it''s fine'" in RequestCopy.powershell("https://e.com/", tricky))
    }

    @Test
    fun powershellWithoutHeadersIsOneLine() {
        val get = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n"
        assertEquals(
            "Invoke-WebRequest -Uri 'https://example.com/'\n",
            RequestCopy.powershell("https://example.com/", get),
        )
    }

    @Test
    fun anEmptyHeadStillProducesAUsableCommand() {
        val command = RequestCopy.curlBash("https://e.com/", "")
        assertEquals("curl 'https://e.com/'\n", command)
    }
}
